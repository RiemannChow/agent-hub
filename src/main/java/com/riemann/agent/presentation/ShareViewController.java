package com.riemann.agent.presentation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 公开页面路由（无 /api 前缀）：
 *   GET /            -> 重定向到对话页 chat.html
 *   GET /share/{id}  -> 重定向到只读分享页 share.html?shareId={id}（链接更简洁）
 */
@RestController
public class ShareViewController {

    @GetMapping("/")
    public Mono<ResponseEntity<Void>> home() {
        return Mono.just(ResponseEntity.status(302)
                .location(URI.create("/chat.html")).build());
    }

    @GetMapping("/share/{shareId}")
    public Mono<ResponseEntity<Void>> view(@PathVariable String shareId) {
        return Mono.just(ResponseEntity.status(302)
                .location(URI.create("/share.html?shareId=" + shareId)).build());
    }
}
