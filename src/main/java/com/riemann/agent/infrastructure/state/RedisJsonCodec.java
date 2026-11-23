package com.riemann.agent.infrastructure.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.presentation.a2a.protocol.Part;
import com.riemann.agent.presentation.a2a.protocol.TextPart;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * JSON codec for shared state. A dedicated mapper copy adds polymorphic type hints for the two
 * sealed/interface families we persist, without changing the public A2A wire format.
 */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "redis")
public class RedisJsonCodec {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = TextPart.class, name = "text"))
    private abstract static class PartMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = AgentEvent.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = AgentEvent.ToolCalling.class, name = "tool_calling"),
        @JsonSubTypes.Type(value = AgentEvent.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = AgentEvent.Token.class, name = "token"),
        @JsonSubTypes.Type(value = AgentEvent.Usage.class, name = "usage"),
        @JsonSubTypes.Type(value = AgentEvent.Notice.class, name = "notice"),
        @JsonSubTypes.Type(value = AgentEvent.Verification.class, name = "verification"),
        @JsonSubTypes.Type(value = AgentEvent.Recovery.class, name = "recovery"),
        @JsonSubTypes.Type(value = AgentEvent.Completed.class, name = "completed")
    })
    private abstract static class EventMixin {}

    private final ObjectMapper mapper;

    public RedisJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.addMixIn(Part.class, PartMixin.class);
        this.mapper.addMixIn(AgentEvent.class, EventMixin.class);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize shared state", e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize shared state", e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
