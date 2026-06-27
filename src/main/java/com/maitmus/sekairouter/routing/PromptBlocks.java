package com.maitmus.sekairouter.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * System 프롬프트를 캐시 플래그 붙은 블록 리스트로 표현한다.
 * 바이트 동일한 선두 블록을 공유하는 경로끼리 Anthropic prefix-cache를 공유한다.
 * cache=false 블록은 cache_control 없이(uncached) 보낸다 — 읽기 상각이 안 되는 저빈도 경로(아레나)용.
 */
public record PromptBlocks(List<Block> blocks) {

    public record Block(String text, boolean cache) {}

    /** 하위호환: prefix/suffix 두 블록(둘 다 캐시). */
    public PromptBlocks(String sharedPrefix, String pathSuffix) {
        this(twoBlocks(sharedPrefix, pathSuffix));
    }

    private static List<Block> twoBlocks(String prefix, String suffix) {
        List<Block> list = new ArrayList<>(2);
        list.add(new Block(prefix, true));
        list.add(new Block(suffix, true));
        return List.copyOf(list);
    }
}
