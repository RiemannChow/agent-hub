package com.riemann.agent.infrastructure.toolkit.policy;

import com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 门禁审计：把 allow / deny / ask 决策落到日志，便于追溯「为什么这个工具没跑」。
 *
 * <p>刻意不往 SSE 事件流里塞事件：SSE 是给最终用户看的对话流，策略审计属于运维信号。 被拒工具自身返回 DENIED 结果，模型与前端都能看到原因，这已经满足可解释性要求。
 */
@Component
public class ConstrainAudit {

    private static final Logger log = LoggerFactory.getLogger(ConstrainAudit.class);

    public void denied(String tool, String reason, AgentCallContext ctx) {
        log.warn(
                "[constrain] DENY tool={} user={} conversation={} depth={} reason={}",
                tool,
                user(ctx),
                conversation(ctx),
                depth(ctx),
                reason);
    }

    public void asked(String tool, String reason, AgentCallContext ctx) {
        log.info(
                "[constrain] ASK tool={} user={} conversation={} reason={}",
                tool,
                user(ctx),
                conversation(ctx),
                reason);
    }

    public void allowed(String tool, AgentCallContext ctx) {
        log.debug(
                "[constrain] ALLOW tool={} user={} conversation={}",
                tool,
                user(ctx),
                conversation(ctx));
    }

    private static String user(AgentCallContext ctx) {
        return ctx == null ? "-" : ctx.userId();
    }

    private static String conversation(AgentCallContext ctx) {
        return ctx == null || ctx.conversationId() == null ? "-" : ctx.conversationId();
    }

    private static int depth(AgentCallContext ctx) {
        return ctx == null ? -1 : ctx.delegationDepth();
    }
}
