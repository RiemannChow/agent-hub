package com.riemann.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riemann.agent.infrastructure.state.CommandBus;
import com.riemann.agent.infrastructure.state.LocalApprovalStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApprovalServiceTest {

    @Test
    void onlyOwnerCanViewApproveOrReject() {
        ApprovalService service = new ApprovalService();
        AtomicBoolean executed = new AtomicBoolean(false);
        String id =
                service.request(
                        "user-a",
                        "conv-1",
                        "seek_commit_sandbox",
                        "merge sandbox",
                        () -> {
                            executed.set(true);
                            return Mono.just("done");
                        });

        assertThat(service.view(id, "user-a")).isPresent();
        assertThat(service.view(id, "user-b")).isEmpty();
        assertThatThrownBy(() -> service.approve(id, "user-b"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(executed).isFalse();

        StepVerifier.create(service.approve(id, "user-a")).expectNext("done").verifyComplete();
        assertThat(executed).isTrue();
        // 审批被消费，不能重复执行副作用
        assertThat(service.view(id, "user-a")).isEmpty();
    }

    @Test
    void rejectNeverRunsTheAction() {
        ApprovalService service = new ApprovalService();
        AtomicBoolean executed = new AtomicBoolean(false);
        String id =
                service.request(
                        "user-a",
                        "conv-1",
                        "save_memory",
                        "save fact",
                        () -> {
                            executed.set(true);
                            return Mono.just("saved");
                        });

        StepVerifier.create(service.reject(id, "user-a"))
                .expectNextMatches(r -> r.contains("已拒绝"))
                .verifyComplete();
        assertThat(executed).isFalse();
        assertThat(service.view(id, "user-a")).isEmpty();
    }

    @Test
    void missingApprovalIsRejected() {
        ApprovalService service = new ApprovalService();
        assertThatThrownBy(() -> service.approve("missing", "user-a"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiredApprovalIsRemovedAndPublished() throws InterruptedException {
        List<String> published = new ArrayList<>();
        CommandBus bus =
                new CommandBus() {
                    @Override
                    public void onApprovalCommand(
                            java.util.function.BiConsumer<String, String> h) {}

                    @Override
                    public void onCancelCommand(java.util.function.Consumer<String> h) {}

                    @Override
                    public Mono<String> executeApprovalOnOwner(
                            String id, String userId, Duration timeout) {
                        return Mono.error(new IllegalStateException("not expected"));
                    }

                    @Override
                    public void publishApprovalResult(
                            String approvalId, String result, boolean failed) {
                        published.add(result);
                    }

                    @Override
                    public void publishCancel(String conversationId) {}
                };
        // A positive TTL is required: the store treats an already-expired metadata as gone.
        ApprovalService service =
                new ApprovalService(new LocalApprovalStore(), bus, Duration.ofMillis(50));
        AtomicBoolean executed = new AtomicBoolean(false);
        String id =
                service.request(
                        "user-a",
                        "conv-1",
                        "seek_commit_sandbox",
                        "merge sandbox",
                        () -> {
                            executed.set(true);
                            return Mono.just("done");
                        });

        assertThat(service.view(id, "user-a")).isPresent();
        Thread.sleep(80); // let the short TTL elapse before the sweep
        service.sweep();

        assertThat(service.view(id, "user-a")).isEmpty();
        assertThat(published).containsExactly("审批已过期，操作未执行");
        assertThat(executed).isFalse();
        assertThatThrownBy(() -> service.approve(id, "user-a"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
