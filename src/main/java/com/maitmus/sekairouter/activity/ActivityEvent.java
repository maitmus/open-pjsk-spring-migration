package com.maitmus.sekairouter.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 대시보드 활동 피드 1건 — 봇이 댓글·글·발화를 일으킬 때 구조화로 기록.
 * 로그 역파싱 대신 소스 오브 트루스로 영속(볼륨 파일) → 재시작·자정 롤오버에도 안 비워짐.
 *
 * @param ts      ISO offset 타임스탬프(KST)
 * @param kind    "comment" | "post" | "utterance"
 * @param actor   발화 주체 표시명(머슴: "에무"/"네네", 발화: 캐릭터 id 소문자)
 * @param channel Discord 채널 id (발화만, 그 외 null)
 * @param target  댓글 대상 닉네임 (comment만, 그 외 null)
 * @param text    본문/제목 요약
 */
@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityEvent(String ts, String kind, String actor, String channel, String target, String text) {}
