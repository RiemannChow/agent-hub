package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 文本单元（A2A 中 kind="text"）。
 * 序列化固定输出 {@code {"kind":"text","text":"..."}}，与协议一致。
 */
public record TextPart(String kind, String text) implements Part {

    @JsonCreator
    public TextPart {
        if (kind == null) {
            kind = "text";
        }
    }

    public TextPart(String text) {
        this("text", text);
    }
}
