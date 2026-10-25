package com.riemann.agent.presentation.a2a.protocol;

import java.util.List;
import java.util.Map;

/**
 * A2A 消息：角色（user / agent）+ 内容单元列表 + 可选元数据。
 * 用于 {@code message/send} 入参以及 {@code Task.history} 中的对话记录。
 */
public record Message(String role, List<Part> parts, Map<String, Object> metadata) {

    /** 拼接所有文本单元，作为驱动智能体的纯文本输入。 */
    public String text() {
        if (parts == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p instanceof TextPart t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /** 从消息元数据取 userId（没有则返回默认）。 */
    public String userIdOrDefault(String fallback) {
        if (metadata != null && metadata.get("userId") instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
