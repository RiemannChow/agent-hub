package com.riemann.agent.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 AgentScope 的消息 / 工具调用 / token 用量转成 Langfuse 需要的 JSON 字符串。
 *
 * <p>Langfuse 的 {@code langfuse.observation.input|output|usage_details} 都按
 * 「JSON 字符串」解析，所以这里统一序列化成字符串再塞进 span 属性。</p>
 *
 * <p>所有输出都会被截断到配置的 {@code maxPayloadChars}，防止超长 prompt
 * 撞上 Langfuse 的单条记录大小限制。</p>
 */
final class LangfusePayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LangfusePayloads() {
    }

    /** 消息列表 → {@code [{"role":"user","content":"..."}]}。 */
    static String messagesToJson(List<Msg> messages, int maxChars) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rows = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (msg == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(4);
            row.put("role", roleOf(msg));
            if (msg.getName() != null && !msg.getName().isBlank()) {
                row.put("name", msg.getName());
            }
            row.put("content", textOf(msg));
            rows.add(row);
        }
        return truncate(toJson(rows), maxChars);
    }

    /** 工具调用列表 → {@code [{"name":"seek_sql_query","arguments":{...}}]}。 */
    static String toolCallsToJson(List<ToolUseBlock> toolCalls, int maxChars) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rows = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock call : toolCalls) {
            if (call == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(4);
            row.put("id", call.getId());
            row.put("name", call.getName());
            row.put("arguments", call.getInput() == null ? Map.of() : call.getInput());
            rows.add(row);
        }
        return truncate(toJson(rows), maxChars);
    }

    /**
     * token 用量 → {@code {"input":123,"output":45,"total":168}}。
     *
     * <p>Langfuse 用这个字段算成本；键名用 input/output/total 会被直接识别。</p>
     */
    static String usageDetailsJson(ChatUsage usage) {
        if (usage == null) {
            return null;
        }
        Map<String, Object> details = new LinkedHashMap<>(4);
        details.put("input", usage.getInputTokens());
        details.put("output", usage.getOutputTokens());
        details.put("total", usage.getTotalTokens());
        if (usage.getCachedTokens() > 0) {
            details.put("cache_read", usage.getCachedTokens());
        }
        return toJson(details);
    }

    static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…[truncated " + (value.length() - maxChars) + " chars]";
    }

    private static String roleOf(Msg msg) {
        MsgRole role = msg.getRole();
        return role == null ? "unknown" : role.name().toLowerCase();
    }

    private static String textOf(Msg msg) {
        try {
            String text = msg.getTextContent();
            return text == null ? "" : text;
        } catch (RuntimeException ex) {
            // 纯结构化（例如只有 tool_use 块）的消息取文本可能抛错，降级成空串而不是打断链路
            return "";
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
