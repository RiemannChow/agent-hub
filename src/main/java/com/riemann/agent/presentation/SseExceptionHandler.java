package com.riemann.agent.presentation;

import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：为普通（非 SSE）端点返回结构化 JSON 错误体。
 *
 * 注意：SSE 端点（/api/agent/chat 等）的异常已由控制器内部在 Flux 上用
 * onErrorResume 包成 error 事件帧（见 AgentChatController），不应再由这里用
 * Flux<ServerSentEvent> 处理——WebFlux 不支持在 @ControllerAdvice 中返回
 * 响应式多值类型做视图解析，会导致 500。因此本 advice 只处理普通请求，
 * 统一输出 {"message","type","status"}。
 */
@ControllerAdvice
public class SseExceptionHandler {

    private final ErrorAttributes errorAttributes;

    public SseExceptionHandler(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(ServerWebExchange exchange, Exception ex) {
        int status = (ex instanceof ResponseStatusException rse)
                ? rse.getStatusCode().value()
                : HttpStatus.INTERNAL_SERVER_ERROR.value();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "unknown error");
        body.put("type", ex.getClass().getSimpleName());
        body.put("status", status);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
