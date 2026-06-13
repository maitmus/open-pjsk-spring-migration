package com.maitmus.sekairouter.proxy;

import com.maitmus.sekairouter.activity.ActivityRecorder;
import com.maitmus.sekairouter.discord.PersonaBotRegistry;
import com.maitmus.sekairouter.persona.CharacterId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxySpeechService {

    private final PersonaBotRegistry registry;
    private final TypingIndicatorService typing;
    private final ActivityRecorder activityRecorder;

    public boolean send(CharacterId character, String channelId, String message) {
        return sendWithReply(character, channelId, message, null).isPresent();
    }

    /**
     * Send a message as the given character, optionally as a Discord reply to another message.
     * Returns the sent message ID on success (used for chaining further replies), or empty on failure.
     */
    public Optional<String> sendWithReply(CharacterId character, String channelId, String message,
                                          String replyToMessageId) {
        TextChannel channel = registry.get(character).getTextChannelById(channelId);
        if (channel == null) {
            log.error("Channel {} not visible to character bot {}", channelId, character);
            typing.stop(character, channelId);
            return Optional.empty();
        }
        try {
            var action = channel.sendMessage(message);
            if (replyToMessageId != null && !replyToMessageId.isBlank()) {
                action = action.setMessageReference(replyToMessageId).mentionRepliedUser(false);
            }
            var sent = action.complete();
            if (replyToMessageId != null && !replyToMessageId.isBlank()) {
                log.info("Sent as {} on {} [reply→{}]: {}", character, channelId, replyToMessageId,
                        abbreviate(message));
            } else {
                log.info("Sent as {} on {}: {}", character, channelId, abbreviate(message));
            }
            activityRecorder.recordUtterance(character.name().toLowerCase(), channelId, message);
            return Optional.of(sent.getId());
        } catch (Exception e) {
            log.error("Failed to send as {} on {}: {}", character, channelId, e.getMessage());
            return Optional.empty();
        } finally {
            typing.stop(character, channelId);
        }
    }

    private String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
