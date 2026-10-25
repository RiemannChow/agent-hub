package com.riemann.agent.presentation.a2a.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 请求。params 保留为原始 {@link JsonNode}，由各方法自行解析，
 * 避免为不同方法定义多套强类型入参。
 */
public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params) {
}
