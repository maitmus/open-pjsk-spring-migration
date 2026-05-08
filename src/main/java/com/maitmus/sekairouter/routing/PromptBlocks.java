package com.maitmus.sekairouter.routing;

/**
 * Two-layer system prompt: a shared prefix that both routing and heartbeat paths
 * use byte-identically, and a path-specific suffix.
 *
 * Anthropic prompt cache works prefix-by-prefix. By emitting the shared content as
 * the first cached block in BOTH paths, we let one cache write (e.g. a heartbeat
 * cold start) serve future routing reads, and vice-versa.
 */
public record PromptBlocks(String sharedPrefix, String pathSuffix) {}
