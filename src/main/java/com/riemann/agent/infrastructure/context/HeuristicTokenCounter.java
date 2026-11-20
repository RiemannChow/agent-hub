package com.riemann.agent.infrastructure.context;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认 token 估算器：中文/日文等表意字符按 1 字 ≈ 1 token，其它字符按 4 字符 ≈ 1 token， 数字按 2 字符 ≈ 1
 * token。对预算裁剪来说估算误差远小于「字符数直接当 token」的偏差， 且没有额外依赖。
 */
@Component
@ConditionalOnProperty(
        prefix = "agent.context",
        name = "token-counting",
        havingValue = "heuristic",
        matchIfMissing = true)
public class HeuristicTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ideographic = 0;
        int digits = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (Character.isIdeographic(c)) {
                ideographic++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else {
                other++;
            }
        }
        return ideographic + (other + 3) / 4 + (digits + 1) / 2;
    }
}
