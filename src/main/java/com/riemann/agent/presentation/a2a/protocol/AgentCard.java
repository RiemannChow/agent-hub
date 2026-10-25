package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A2A Agent Card：发布在 {@code /.well-known/agent.json} 的智能体自描述元数据。
 * 对端智能体/客户端据此了解本智能体的能力、技能、输入输出模态与 A2A 端点。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(
        String protocolVersion,
        String name,
        String description,
        String url,
        String version,
        AgentCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<AgentSkill> skills
) {
}
