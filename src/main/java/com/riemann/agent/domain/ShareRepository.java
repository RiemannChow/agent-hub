package com.riemann.agent.domain;

import reactor.core.publisher.Mono;

/**
 * 分享快照的 Repository Port，定义于 Domain 层。
 * 由 Infrastructure 层提供具体实现（默认文件落地）。
 */
public interface ShareRepository {

    Mono<Void> save(SharedConversation share);

    Mono<SharedConversation> findById(String shareId);
}
