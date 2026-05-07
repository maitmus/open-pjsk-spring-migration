package com.maitmus.sekairouter.proxy;

import com.maitmus.sekairouter.discord.PersonaBotRegistry;
import com.maitmus.sekairouter.persona.CharacterId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxySpeechService {

    private final PersonaBotRegistry registry;
    private final TypingIndicatorService typing;

    public boolean send(CharacterId character, String channelId, String message) {
        TextChannel channel = registry.get(character).getTextChannelById(channelId);
        if (channel == null) {
            log.error("Channel {} not visible to character bot {}", channelId, character);
            typing.stop(character, channelId);
            return false;
        }
        try {
            channel.sendMessage(message).complete();
            log.info("Sent as {} on {}: {}", character, channelId, abbreviate(message));
            return true;
        } catch (Exception e) {
            log.error("Failed to send as {} on {}: {}", character, channelId, e.getMessage());
            return false;
        } finally {
            typing.stop(character, channelId);
        }
    }

    private String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
