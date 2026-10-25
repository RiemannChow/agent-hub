package com.riemann.agent.presentation.a2a.protocol;

/**
 * JSON-RPC 2.0 错误对象，采用标准错误码区间。
 */
public record JsonRpcError(int code, String message, Object data) {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32000;

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }

    public static JsonRpcError of(int code, String message, Object data) {
        return new JsonRpcError(code, message, data);
    }
}
