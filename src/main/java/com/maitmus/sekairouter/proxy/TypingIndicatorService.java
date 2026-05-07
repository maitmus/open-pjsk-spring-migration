package com.maitmus.sekairouter.proxy;

import com.maitmus.sekairouter.discord.PersonaBotRegistry;
import com.maitmus.sekairouter.persona.CharacterId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class TypingIndicatorService {

    private static final long INTERVAL_SECONDS = 5L;

    private final PersonaBotRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> active = new ConcurrentHashMap<>();

    public void start(CharacterId character, String channelId) {
        String key = key(character, channelId);
        TextChannel channel = registry.get(character).getTextChannelById(channelId);
        if (channel == null) {
            log.warn("Channel {} not found for {}", channelId, character);
            return;
        }
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> channel.sendTyping().queue(
                        success -> {},
                        err -> log.debug("typing failed: {}", err.getMessage())),
                0, INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        active.put(key, task);
    }

    public void stop(CharacterId character, String channelId) {
        ScheduledFuture<?> task = active.remove(key(character, channelId));
        if (task != null) task.cancel(false);
    }

    private String key(CharacterId c, String ch) {
        return c.name() + ":" + ch;
    }
}
