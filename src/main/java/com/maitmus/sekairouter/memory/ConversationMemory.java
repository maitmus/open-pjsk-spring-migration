package com.maitmus.sekairouter.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ConversationMemory {

    private final int maxTurnsPerChannel;
    private final ConcurrentMap<String, Deque<ConversationTurn>> store = new ConcurrentHashMap<>();

    public ConversationMemory(@Value("${conversation.max-turns:5}") int maxTurnsPerChannel) {
        this.maxTurnsPerChannel = maxTurnsPerChannel;
    }

    public void append(String channelId, ConversationTurn turn) {
        Deque<ConversationTurn> deque = store.computeIfAbsent(channelId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(turn);
            while (deque.size() > maxTurnsPerChannel) {
                deque.pollFirst();
            }
        }
    }

    public List<ConversationTurn> getRecent(String channelId) {
        Deque<ConversationTurn> deque = store.get(channelId);
        if (deque == null) return List.of();
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
