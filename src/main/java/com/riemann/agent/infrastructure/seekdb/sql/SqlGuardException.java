package com.riemann.agent.infrastructure.seekdb.sql;

/**
 * SQL 被守卫拒绝时抛出。
 *
 * <p>异常 message 是「可以直接回给模型看」的中文说明：工具层会把它原样返回给 LLM，
 * 让模型据此改写 SQL 重试，而不是抛一个没有信息量的 500。因此这里的文案
 * 不包含堆栈细节，也不回显原始 SQL 之外的服务端信息。</p>
 */
public class SqlGuardException extends RuntimeException {

    public SqlGuardException(String message) {
        super(message);
    }
}
