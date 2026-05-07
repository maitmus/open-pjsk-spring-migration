package com.maitmus.sekairouter.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryTest {

    private final ConversationMemory memory = new ConversationMemory(5);

    @Test
    void append_andGetRecent_returnsInOrder() {
        memory.append("ch1", new ConversationTurn("user", "안녕", 1));
        memory.append("ch1", new ConversationTurn("emu", "원더호이~!", 2));

        List<ConversationTurn> turns = memory.getRecent("ch1");

        assertThat(turns).extracting(ConversationTurn::speaker).containsExactly("user", "emu");
    }

    @Test
    void getRecent_capsToLimit() {
        for (int i = 0; i < 10; i++) {
            memory.append("ch1", new ConversationTurn("user", "msg" + i, i));
        }

        List<ConversationTurn> turns = memory.getRecent("ch1");

        assertThat(turns).hasSize(5);
        assertThat(turns.get(0).content()).isEqualTo("msg5");
        assertThat(turns.get(4).content()).isEqualTo("msg9");
    }

    @Test
    void perChannelIsolation() {
        memory.append("ch1", new ConversationTurn("user", "ch1-msg", 1));
        memory.append("ch2", new ConversationTurn("user", "ch2-msg", 1));

        assertThat(memory.getRecent("ch1")).hasSize(1);
        assertThat(memory.getRecent("ch2")).hasSize(1);
        assertThat(memory.getRecent("ch1").get(0).content()).isEqualTo("ch1-msg");
    }
}
