package com.maitmus.sekairouter.routing;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonQuoteRepairTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    @Test
    void escapes_unescaped_inner_quotes_so_content_survives() throws Exception {
        // 라이브 실패 케이스(2026-06-26 네네 글): content 안에 비이스케이프 큰따옴표 → 65자 절단됐던 것.
        String raw = "{\"content\": \"오늘 스텝이 꼬였어. 에무가 \"네네쨩 괜찮아?\" 물어봤는데 더 신경 쓰이더라. 고치면 되지.\"}";
        String repaired = JsonQuoteRepair.escapeInnerQuotes(raw);

        // 보정 후엔 정상 JSON으로 파싱돼 content가 통째로 살아야 함(절단 X).
        String content = MAPPER.readTree(repaired).get("content").asText();
        assertThat(content).contains("네네쨩 괜찮아?").contains("고치면 되지");
        assertThat(content).doesNotEndWith("에무가 ");
    }

    @Test
    void leaves_valid_escaped_json_unchanged_idempotent() throws Exception {
        // 이미 \" 로 올바르게 이스케이프된 JSON은 건드리지 않는다(멱등).
        String valid = "{\"reasoning\":\"r\",\"content\":\"에무가 \\\"안녕\\\" 했어\",\"shouldPost\":true}";
        String repaired = JsonQuoteRepair.escapeInnerQuotes(valid);
        assertThat(repaired).isEqualTo(valid);
        assertThat(MAPPER.readTree(repaired).get("content").asText()).isEqualTo("에무가 \"안녕\" 했어");
    }

    @Test
    void preserves_plain_json_without_quotes() {
        String plain = "{\"title\":\"벚꽃 산책기\",\"content\":\"오늘 벚꽃 만개\"}";
        assertThat(JsonQuoteRepair.escapeInnerQuotes(plain)).isEqualTo(plain);
    }

    @Test
    void handles_null_and_empty() {
        assertThat(JsonQuoteRepair.escapeInnerQuotes(null)).isNull();
        assertThat(JsonQuoteRepair.escapeInnerQuotes("")).isEmpty();
    }
}
