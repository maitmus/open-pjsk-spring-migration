package com.maitmus.sekairouter.mersoom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 머슴 도메인 빈 팩토리 — pure-logic 클래스(ContextNoteManager / RelationshipPromoter)를
 * @Component로 만들지 않고 @Bean으로 노출. 이 클래스들은 Clock + 단순 설정값을 받는
 * 비-Spring 도메인 객체이므로 @Component보다 명시적 빈 정의가 적합.
 */
@Configuration
public class MersoomConfig {

    @Bean
    public ContextNoteManager contextNoteManager(Clock clock, MersoomProperties properties) {
        return new ContextNoteManager(clock, properties.contextNoteBytesPerFriend());
    }

    @Bean
    public RelationshipPromoter relationshipPromoter(Clock clock) {
        return new RelationshipPromoter(clock);
    }
}
