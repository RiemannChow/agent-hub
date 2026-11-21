package com.riemann.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riemann.agent.domain.AgentEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** 模型可用性探针：触发条件精确、成功/失败都有明确文案且不抛错。 */
class ModelHealthProbeTest {

    private static final String OK = "服务正常，智能体已就绪，请继续提问吧。";
    private static final String FAIL = "模型服务暂时不可用，请稍后再试。";

    private Model model;
    private ModelHealthProbe probe;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        probe = new ModelHealthProbe(model, true, Duration.ofSeconds(2), OK, FAIL);
    }

    // ---------- 触发条件：只认整条精确问候 ----------

    @Test
    void exactGreetingsMatch() {
        assertThat(ModelHealthProbe.isGreeting("你好")).isTrue();
        assertThat(ModelHealthProbe.isGreeting("hello")).isTrue();
        assertThat(ModelHealthProbe.isGreeting("  Hello!  ")).isTrue();
        assertThat(ModelHealthProbe.isGreeting("你好！")).isTrue();
        assertThat(ModelHealthProbe.isGreeting("hi~")).isTrue();
    }

    @Test
    void greetingsWithATaskDoNotMatch() {
        assertThat(ModelHealthProbe.isGreeting("你好，帮我查下 GMV")).isFalse();
        assertThat(ModelHealthProbe.isGreeting("hello world")).isFalse();
        assertThat(ModelHealthProbe.isGreeting("你还好吗")).isFalse();
        assertThat(ModelHealthProbe.isGreeting(null)).isFalse();
        assertThat(ModelHealthProbe.isGreeting("")).isFalse();
    }

    // ---------- 探针行为 ----------

    @Test
    void reachableReturnsAvailableTextAndCompletes() {
        ChatResponse resp =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("pong").build()))
                        .build();
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(Flux.just(resp));

        StepVerifier.create(probe.probe("c-1"))
                .expectNext(new AgentEvent.Token(OK))
                .expectNext(new AgentEvent.Completed("c-1"))
                .verifyComplete();

        verify(model).stream(anyList(), anyList(), any(GenerateOptions.class));
    }

    @Test
    void gatewayErrorReturnsUnavailableTextWithoutThrowing() {
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(Flux.error(new RuntimeException("401 unauthorized")));

        StepVerifier.create(probe.probe("c-2"))
                .expectNext(new AgentEvent.Token(FAIL))
                .expectNext(new AgentEvent.Completed("c-2"))
                .verifyComplete();
    }

    @Test
    void timeoutReturnsUnavailableTextWithoutThrowing() {
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(Flux.never());

        StepVerifier.create(probe.probe("c-3"))
                .expectNext(new AgentEvent.Token(FAIL))
                .expectNext(new AgentEvent.Completed("c-3"))
                .verifyComplete();
    }

    @Test
    void disabledProbeIsReported() {
        ModelHealthProbe disabled =
                new ModelHealthProbe(model, false, Duration.ofSeconds(1), OK, FAIL);
        assertThat(disabled.isEnabled()).isFalse();
    }
}
