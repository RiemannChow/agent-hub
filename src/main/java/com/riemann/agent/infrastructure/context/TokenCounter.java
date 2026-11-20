package com.riemann.agent.infrastructure.context;

/** 上下文窗口的 token 计数器。不引入重型 tokenizer 依赖，按配置选择估算策略。 */
public interface TokenCounter {
    int count(String text);
}
