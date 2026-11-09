package com.riemann.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.riemann.agent.application.quota.AgentQuotaService;
import com.riemann.agent.domain.AccessDeniedException;
import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.domain.AgentState;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.domain.ConversationRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Regression tests for issue #19: a conversation id must not grant access to another user's data.
 */
class ConversationOwnershipTest {

    private static final ConversationId ID = new ConversationId("c-1");

    private static Conversation ownedBy(String userId) {
        return new Conversation(ID, userId, null, AgentState.initial("seek-agent"));
    }

    private static ConversationRepository repositoryReturning(Conversation conv) {
        ConversationRepository repo =
                mock(
                        ConversationRepository.class,
                        withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(Mono.just(conv)).when(repo).findById(any());
        return repo;
    }

    private static ConversationAppService service(ConversationRepository repo) {
        return new ConversationAppService(
                repo,
                mock(AgentOrchestrationService.class),
                mock(MemoryExtractor.class),
                mock(MemoryService.class),
                mock(AgentQuotaService.class));
    }

    @Test
    void findByIdForUserAllowsOwnerAndRejectsEveryoneElse() {
        ConversationRepository repo = repositoryReturning(ownedBy("alice"));

        StepVerifier.create(repo.findByIdForUser(ID, "alice"))
                .assertNext(c -> assertThat(c.userId()).isEqualTo("alice"))
                .verifyComplete();
        StepVerifier.create(repo.findByIdForUser(ID, "bob"))
                .verifyError(AccessDeniedException.class);
        StepVerifier.create(repo.findByIdForUser(ID, "  "))
                .verifyError(IllegalArgumentException.class);
    }

    @Test
    void chatRejectsForeignConversationInsteadOfAppendingToIt() {
        ConversationAppService svc = service(repositoryReturning(ownedBy("alice")));

        StepVerifier.create(svc.chat("bob", "hello", ID)).verifyError(AccessDeniedException.class);
    }

    @Test
    void readingForeignConversationIsRejected() {
        ConversationAppService svc = service(repositoryReturning(ownedBy("alice")));

        StepVerifier.create(svc.getConversationForUser(ID, "bob"))
                .verifyError(AccessDeniedException.class);
        StepVerifier.create(svc.getConversationForUser(ID, "alice"))
                .expectNextCount(1)
                .verifyComplete();
    }
}
