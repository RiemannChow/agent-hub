package com.riemann.agent.infrastructure.toolkit.registry;

import io.opentelemetry.api.common.AttributeKey;

/** OTel attribute keys used to stamp tool version information on spans. */
public final class ToolVersionAttributes {

    public static final String TOOL_NAME = "agent.tool.name";
    public static final String TOOL_VERSION = "agent.tool.version";
    public static final AttributeKey<String> KEY_TOOL_NAME = AttributeKey.stringKey(TOOL_NAME);
    public static final AttributeKey<String> KEY_TOOL_VERSION =
            AttributeKey.stringKey(TOOL_VERSION);

    private ToolVersionAttributes() {}
}
