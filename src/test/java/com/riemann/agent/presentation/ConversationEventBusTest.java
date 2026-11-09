package com.riemann.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/** Regression tests for issue #21: the event bus must stay bounded and release completed turns. */
class ConversationEventBusTest {

    private static final ConversationId ID = new ConversationId("c-1");

    private static ConversationEventBus bus(ConversationEventBusProperties props) {
        return new ConversationEventBus(props);
    }

    private static AgentEvent token(String text) {
        return new AgentEvent.Token(text);
    }

    @Test
    void releasingASinkRemovesTheEntry() {
        ConversationEventBus bus = bus(new ConversationEventBusProperties());
        Sinks.Many<AgentEvent> sink = bus.register(ID);
        sink.tryEmitNext(token("hello"));
        assertThat(bus.size()).isEqualTo(1);

        bus.remove(ID, sink);
        assertThat(bus.size()).isZero();
    }

    @Test
    void removingAnOldSinkNeverDeletesANewerTurn() {
        ConversationEventBus bus = bus(new ConversationEventBusProperties());
        Sinks.Many<AgentEvent> first = bus.register(ID);
        Sinks.Many<AgentEvent> second = bus.register(ID);

        bus.remove(ID, first);

        assertThat(bus.size()).isEqualTo(1);
        // The newer sink is still the registered one.
        assertThat(bus.flux(ID)).isNotNull();
        second.tryEmitComplete();
    }

    @Test
    void replayIsBoundedInsteadOfRetainingTheWholeTurn() {
        ConversationEventBusProperties props = new ConversationEventBusProperties();
        props.setMaxReplayEvents(3);
        ConversationEventBus bus = bus(props);
        Sinks.Many<AgentEvent> sink = bus.register(ID);
        for (int i = 0; i < 10; i++) {
            sink.tryEmitNext(token("t" + i));
        }
        sink.tryEmitComplete();

        List<String> replayed =
                sink.asFlux().collectList().block().stream()
                        .map(e -> ((AgentEvent.Token) e).delta())
                        .toList();
        assertThat(replayed).containsExactly("t7", "t8", "t9");
    }

    @Test
    void lateSubscriberStillReplaysTheCurrentTurn() {
        ConversationEventBus bus = bus(new ConversationEventBusProperties());
        Sinks.Many<AgentEvent> sink = bus.register(ID);
        sink.tryEmitNext(token("first"));
        sink.tryEmitNext(token("second"));
        sink.tryEmitComplete();

        List<String> replayed =
                bus.flux(ID).collectList().block().stream()
                        .map(e -> ((AgentEvent.Token) e).delta())
                        .toList();
        assertThat(replayed).containsExactly("first", "second");
    }

    @Test
    void expiredEntriesAreSwept() {
        ConversationEventBusProperties props = new ConversationEventBusProperties();
        props.setTtl(Duration.ofMinutes(10));
        ConversationEventBus bus = bus(props);
        bus.register(ID);
        assertThat(bus.size()).isEqualTo(1);

        bus.sweep(Instant.now().plus(Duration.ofMinutes(10)));

        assertThat(bus.size()).isZero();
    }

    @Test
    void activeConversationsAreCapped() {
        ConversationEventBusProperties props = new ConversationEventBusProperties();
        props.setMaxActiveConversations(2);
        ConversationEventBus bus = bus(props);
        bus.register(new ConversationId("a"));
        bus.register(new ConversationId("b"));
        bus.register(new ConversationId("c"));

        assertThat(bus.size()).isEqualTo(2);
    }

    @Test
    void invalidConfigurationIsClamped() {
        ConversationEventBusProperties props = new ConversationEventBusProperties();
        props.setMaxReplayEvents(0);
        props.setMaxActiveConversations(-5);
        props.setTtl(Duration.ZERO);

        assertThat(props.getMaxReplayEvents()).isEqualTo(1);
        assertThat(props.getMaxActiveConversations()).isEqualTo(1);
        assertThat(props.getTtl()).isEqualTo(Duration.ofMinutes(10));
    }
}
