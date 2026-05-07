package com.maitmus.sekairouter.discord;

import com.maitmus.sekairouter.persona.CharacterId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaBotRegistry {

    private final Map<CharacterId, JDA> bots;

    public JDA get(CharacterId id) {
        JDA jda = bots.get(id);
        if (jda == null) {
            throw new IllegalStateException("No JDA registered for character: " + id);
        }
        return jda;
    }

    public Map<CharacterId, JDA> all() {
        return bots;
    }
}
