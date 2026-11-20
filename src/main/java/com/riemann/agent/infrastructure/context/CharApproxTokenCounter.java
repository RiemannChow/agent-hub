package com.riemann.agent.infrastructure.context;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 粗略计数：所有字符按 4 字符 ≈ 1 token，适合英文为主且不想要中文特化的场景。 */
@Component
@ConditionalOnProperty(prefix = "agent.context", name = "token-counting", havingValue = "char")
public class CharApproxTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }
}
