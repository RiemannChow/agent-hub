package com.riemann.agent.infrastructure.toolkit.tools;

import com.riemann.agent.infrastructure.seekdb.SeekDbConversationRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 为当前 Agent 创建 SeekDB 的 COW 沙箱数据库快照，用于隔离实验性写操作。
 */
@Component
@Profile("seekdb")
public class SeekForkSandboxTool {

    private final SeekDbConversationRepository seekDb;

    public SeekForkSandboxTool(SeekDbConversationRepository seekDb) {
        this.seekDb = seekDb;
    }

    @Tool(name = "seek_fork_sandbox",
          description = "为当前 Agent 创建 SeekDB 的 COW 沙箱数据库快照，用于隔离实验性写操作（如改库、写回记忆）。"
                  + "仅在需要“先试后合并”的写操作前调用，普通问数/检索不需要。")
    public Mono<String> seekForkSandbox(
            @ToolParam(name = "agent_id", description = "Agent 标识") String agentId) {
        return seekDb.forkSandbox(agentId)
                     .thenReturn("forked sandbox: agent_" + agentId + "_sandbox");
    }
}
