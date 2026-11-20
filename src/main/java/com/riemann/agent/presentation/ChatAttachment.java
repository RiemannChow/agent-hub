package com.riemann.agent.presentation;

/** 一次对话携带的附件。data 为纯 base64（不含 data: 前缀），由后端校验大小与类型。 */
public record ChatAttachment(String name, String mimeType, String data) {}
