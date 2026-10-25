package com.riemann.agent.presentation.a2a.protocol;

/**
 * JSON-RPC 2.0 响应。同一时刻仅含 {@code result} 或 {@code error} 之一。
 */
public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {

    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    public static JsonRpcResponse error(Object id, JsonRpcError error) {
        return new JsonRpcResponse("2.0", id, null, error);
    }
}
