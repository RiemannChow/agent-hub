package com.riemann.agent.presentation.a2a.protocol;

import java.util.List;

/**
 * A2A 技能描述：一个可被该智能体执行的原子能力（对应后端的某个工具/问数能力）。
 */
public record AgentSkill(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes
) {
}
