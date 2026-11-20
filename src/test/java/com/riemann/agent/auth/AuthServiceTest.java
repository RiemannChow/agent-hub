package com.riemann.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PHONE = "13800138000";

    @Mock private UserRepository users;
    @Mock private SessionRepository sessions;
    @Mock private WechatTicketRepository tickets;
    @Mock private SmsCodeRepository sms;
    @Mock private SmsSender sender;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, sessions, tickets, sms, sender, properties(true));
    }

    private AuthProperties properties(boolean demoConfirm) {
        return new AuthProperties(
                new AuthProperties.Sms("demo", "", "", Duration.ofSeconds(60), 5, 5),
                new AuthProperties.Wechat(demoConfirm, "callback-secret", Duration.ofMinutes(5)),
                new AuthProperties.Csrf(true));
    }

    @Test
    void resendBeforeCooldownIsRejected() {
        long now = System.currentTimeMillis();
        when(sms.findByPhone(PHONE))
                .thenReturn(Flux.just(new SmsCode("id", PHONE, "123456", now, now + 60_000, 0)));

        StepVerifier.create(service.sendSmsCode(PHONE))
                .expectErrorSatisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .verify();
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    void exhaustedAttemptsInvalidateTheCode() {
        long now = System.currentTimeMillis();
        SmsCode exhausted = new SmsCode("id", PHONE, "123456", now - 1_000, now + 60_000, 5);
        when(sms.findByPhone(PHONE)).thenReturn(Flux.just(exhausted));
        when(sms.delete(exhausted)).thenReturn(Mono.empty());

        StepVerifier.create(service.loginByPhone(PHONE, "123456"))
                .expectErrorSatisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .verify();
        verify(sms).delete(exhausted);
    }

    @Test
    void wrongCodeIncrementsAttempts() {
        long now = System.currentTimeMillis();
        SmsCode current = new SmsCode("id", PHONE, "123456", now - 1_000, now + 60_000, 0);
        when(sms.findByPhone(PHONE)).thenReturn(Flux.just(current));
        when(sms.save(any(SmsCode.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.loginByPhone(PHONE, "000000"))
                .expectErrorSatisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.UNAUTHORIZED))
                .verify();
        verify(sms)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                code -> code.attempts() == 1 && code.code().equals("123456")));
    }

    @Test
    void demoWechatConfirmCanBeDisabled() {
        service = new AuthService(users, sessions, tickets, sms, sender, properties(false));

        StepVerifier.create(service.confirmScan("ticket-1"))
                .expectErrorSatisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode())
                                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(tickets, never()).findById(anyString());
    }
}
