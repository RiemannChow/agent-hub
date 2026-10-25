package com.riemann.agent.presentation.a2a.protocol;

import java.util.List;

/**
 * A2A 产物：一次任务产出的结构化结果（如最终回答文本）。
 * 流式场景下通过 {@code append} + {@code lastChunk} 分块增量推送。
 */
public record Artifact(
        String artifactId,
        String name,
        List<Part> parts,
        Integer index,
        Boolean append,
        Boolean lastChunk
) {

    /** 便捷构造：单个文本单元的产物。 */
    public static Artifact text(String artifactId, String text, Integer index, Boolean append, Boolean lastChunk) {
        return new Artifact(artifactId, null, List.of(new TextPart(text)), index, append, lastChunk);
    }
}
