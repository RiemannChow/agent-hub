package com.riemann.agent.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Random;
import java.util.UUID;

/**
 * 登录用例：手机号（演示验证码）+ 微信（模拟扫码）两种登录方式。
 * 用户信息、会话、票据、验证码均落 MongoDB。
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final SessionRepository sessions;
    private final WechatTicketRepository tickets;
    private final SmsCodeRepository sms;
    private final Random random = new Random();

    private static final long SESSION_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final long CODE_TTL = 5L * 60 * 1000;
    private static final long TICKET_TTL = 5L * 60 * 1000;

    public AuthService(UserRepository users, SessionRepository sessions,
                       WechatTicketRepository tickets, SmsCodeRepository sms) {
        this.users = users;
        this.sessions = sessions;
        this.tickets = tickets;
        this.sms = sms;
    }

    /** 发送验证码（演示：直接返回 code）。 */
    public Mono<String> sendSmsCode(String phone) {
        if (!isValidPhone(phone)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式不正确"));
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        long now = System.currentTimeMillis();
        SmsCode c = new SmsCode(uuid(), phone, code, now, now + CODE_TTL);
        return sms.save(c).map(x -> code);
    }

    /** 手机号 + 验证码登录，返回 token。 */
    public Mono<AuthResult> loginByPhone(String phone, String code) {
        if (!isValidPhone(phone)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式不正确"));
        }
        if (code == null || code.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入验证码"));
        }
        return sms.findByPhone(phone).next()
                .flatMap(c -> {
                    if (c.expiresAt() < System.currentTimeMillis() || !c.code().equals(code)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期"));
                    }
                    return sms.delete(c).thenReturn(c);
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先获取验证码")))
                .flatMap(c -> users.findByPhone(phone)
                        .switchIfEmpty(Mono.defer(() -> {
                            long now = System.currentTimeMillis();
                            User u = new User(null, phone, null, "用户" + phone.substring(phone.length() - 4), now, now);
                            return users.save(u);
                        }))
                        .flatMap(u -> createSession(u).map(token -> new AuthResult(token, toDto(u)))));
    }

    /** 创建微信扫码票据。 */
    public Mono<WechatTicketView> createTicket() {
        String ticket = uuid();
        long now = System.currentTimeMillis();
        WechatTicket t = new WechatTicket(ticket, "PENDING", null, null, null, now, now + TICKET_TTL);
        return tickets.save(t).map(x -> new WechatTicketView(ticket));
    }

    /** 查询微信票据状态；已确认则附带 token。 */
    public Mono<TicketStatus> getStatus(String ticket) {
        return tickets.findById(ticket)
                .flatMap(t -> {
                    if (t.expiresAt() < System.currentTimeMillis()) {
                        return Mono.just(new TicketStatus("EXPIRED", null, null));
                    }
                    if (!"CONFIRMED".equals(t.status()) || t.token() == null) {
                        return Mono.just(new TicketStatus(t.status(), null, null));
                    }
                    return users.findById(t.userId()).map(u -> new TicketStatus("CONFIRMED", t.token(), toDto(u)));
                })
                .switchIfEmpty(Mono.just(new TicketStatus("UNKNOWN", null, null)));
    }

    /** 模拟微信扫码确认（相当于微信回调）。 */
    public Mono<AuthResult> confirmScan(String ticket) {
        return tickets.findById(ticket)
                .flatMap(t -> {
                    if (t == null || t.expiresAt() < System.currentTimeMillis()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已失效，请刷新"));
                    }
                    String openId = "sim_" + ticket;
                    return users.findByWechatOpenId(openId)
                            .switchIfEmpty(Mono.defer(() -> {
                                long now = System.currentTimeMillis();
                                User u = new User(null, null, openId, "微信用户", now, now);
                                return users.save(u);
                            }))
                            .flatMap(u -> createSession(u).flatMap(token -> {
                                WechatTicket updated = new WechatTicket(t.ticket(), "CONFIRMED", openId, u.id(), token, t.createdAt(), t.expiresAt());
                                return tickets.save(updated).thenReturn(new AuthResult(token, toDto(u)));
                            }));
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码不存在")));
    }

    /** 校验 token，返回 userId；失效返回 empty。 */
    public Mono<String> validateToken(String token) {
        if (token == null) return Mono.empty();
        return sessions.findById(token)
                .flatMap(s -> s.expiresAt() < System.currentTimeMillis() ? Mono.empty() : Mono.just(s.userId()));
    }

    public Mono<UserDto> me(String token) {
        return validateToken(token)
                .flatMap(users::findById)
                .map(this::toDto)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效")));
    }

    public Mono<Void> logout(String token) {
        if (token == null) return Mono.empty();
        return sessions.findById(token).flatMap(sessions::delete).then();
    }

    private Mono<String> createSession(User u) {
        String token = uuid();
        long now = System.currentTimeMillis();
        return sessions.save(new LoginSession(token, u.id(), now, now + SESSION_TTL)).map(x -> token);
    }

    private UserDto toDto(User u) { return new UserDto(u.id(), u.phone(), u.nickname()); }

    private boolean isValidPhone(String p) { return p != null && p.matches("^1[3-9]\\d{9}$"); }

    private String uuid() { return UUID.randomUUID().toString().replace("-", ""); }

    // ===== DTO =====
    public record AuthResult(String token, UserDto user) {}
    public record UserDto(String id, String phone, String nickname) {}
    public record WechatTicketView(String ticket) {}
    public record TicketStatus(String status, String token, UserDto user) {}
}
