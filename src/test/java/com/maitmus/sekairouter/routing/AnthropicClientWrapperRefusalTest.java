package com.maitmus.sekairouter.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicClientWrapperRefusalTest {

    @Test
    void first_answer_is_returned_without_retry() {
        var calls = new AtomicInteger();
        var empties = new AtomicInteger();
        String text = AnthropicClientWrapper.retryOnceIfEmpty(
                () -> { calls.incrementAndGet(); return Optional.of("ok"); },
                empties::incrementAndGet, "No text content in response");
        assertThat(text).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(empties).hasValue(0);
    }

    @Test
    void empty_first_answer_is_dumped_and_retried_once() {
        var answers = new ArrayDeque<>(List.of(Optional.<String>empty(), Optional.of("second")));
        var empties = new AtomicInteger();
        String text = AnthropicClientWrapper.retryOnceIfEmpty(answers::poll, empties::incrementAndGet,
                "No text content in response");
        assertThat(text).isEqualTo("second");
        assertThat(empties).hasValue(1);
        assertThat(answers).isEmpty();
    }

    @Test
    void two_empty_answers_throw_after_single_retry() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> AnthropicClientWrapper.retryOnceIfEmpty(
                () -> { calls.incrementAndGet(); return Optional.empty(); }, () -> {},
                "No text content in response"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No text content in response");
        assertThat(calls).hasValue(2);
    }

    @Test
    void refusal_dump_writes_user_prompt_with_stop_reason(@TempDir Path dir) throws Exception {
        Path written = AnthropicClientWrapper.dumpRefusal(dir.resolve("refusals"), "refusal", "## 이번 글 소재\n후보 1");
        assertThat(written).isNotNull();
        String body = Files.readString(written);
        assertThat(body).startsWith("stop_reason=refusal\n");
        assertThat(body).contains("## 이번 글 소재\n후보 1");
    }

    @Test
    void refusal_dump_failure_does_not_throw(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");   // 디렉터리 자리에 파일 → 생성 실패
        assertThat(AnthropicClientWrapper.dumpRefusal(blocker, "refusal", "u")).isNull();
    }
}
