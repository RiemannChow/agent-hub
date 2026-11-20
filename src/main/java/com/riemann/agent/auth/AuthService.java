package com.riemann.agent.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 登录用例：手机号验证码 + 微信扫码（真实回调或受控 demo）。
 *
 * <p>验证码不再无条件明文回传：只有 sms.mode=demo 时登录页才展示；http 模式由外部网关下发。 同时限制重发频率、每小时发送量和连续输错次数，避免验证码接口被爆破。
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final SessionRepository sessions;
    private final WechatTicketRepository tickets;
    private final SmsCodeRepository sms;
    private final SmsSender smsSender;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    private static final long SESSION_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final long CODE_TTL = 5L * 60 * 1000;
    private static final long TICKET_TTL = 5L * 60 * 1000;

    public AuthService(
            UserRepository users,
            SessionRepository sessions,
            WechatTicketRepository tickets,
            SmsCodeRepository sms,
            SmsSender smsSender,
            AuthProperties properties) {
        this.users = users;
        this.sessions = sessions;
        this.tickets = tickets;
        this.sms = sms;
        this.smsSender = smsSender;
        this.properties = properties;
    }

    /** 发送验证码。受重发冷却和每小时配额约束；网关模式不在响应中返回明文。 */
    public Mono<String> sendSmsCode(String phone) {
        if (!isValidPhone(phone)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式不正确"));
        }
        AuthProperties.Sms cfg = smsCfg();
        long now = System.currentTimeMillis();
        return sms.findByPhone(phone)
                .collectList()
                .flatMap(
                        codes -> {
                            SmsCode latest = latest(codes);
                            if (latest != null
                                    && now - latest.createdAt() < cfg.resendCooldown().toMillis()) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试"));
                            }
                            long hourAgo = now - Duration.ofHours(1).toMillis();
                            long sentThisHour =
                                    codes.stream().filter(c -> c.createdAt() > hourAgo).count();
                            if (sentThisHour >= cfg.maxPerHour()) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.TOO_MANY_REQUESTS, "一小时内发送次数过多"));
                            }
                            String code = String.format("%06d", random.nextInt(1_000_000));
                            SmsCode c = new SmsCode(uuid(), phone, code, now, now + CODE_TTL, 0);
                            // 先发后存：网关失败时不占用该手机号的重发配额。
                            return smsSender.send(phone, code).then(sms.save(c)).thenReturn(code);
                        });
    }

    /** 手机号 + 验证码登录。连续输错达到上限即作废验证码。 */
    public Mono<AuthResult> loginByPhone(String phone, String code) {
        if (!isValidPhone(phone)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式不正确"));
        }
        if (code == null || code.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入验证码"));
        }
        int maxAttempts = smsCfg().maxAttempts();
        long now = System.currentTimeMillis();
        return sms.findByPhone(phone)
                .collectList()
                .flatMap(
                        codes -> {
                            SmsCode c = latest(codes);
                            if (c == null) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "请先获取验证码"));
                            }
                            if (c.expiresAt() < now) {
                                return sms.delete(c)
                                        .then(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.UNAUTHORIZED,
                                                                "验证码已过期")));
                            }
                            if (c.attempts() >= maxAttempts) {
                                return sms.delete(c)
                                        .then(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.TOO_MANY_REQUESTS,
                                                                "尝试次数过多，请重新获取验证码")));
                            }
                            if (!c.code().equals(code)) {
                                SmsCode bumped =
                                        new SmsCode(
                                                c.id(),
                                                c.phone(),
                                                c.code(),
                                                c.createdAt(),
                                                c.expiresAt(),
                                                c.attempts() + 1);
                                return sms.save(bumped)
                                        .then(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.UNAUTHORIZED, "验证码无效")));
                            }
                            return sms.delete(c).then(loginUserByPhone(phone));
                        });
    }

    private Mono<AuthResult> loginUserByPhone(String phone) {
        return users.findByPhone(phone)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    long now = System.currentTimeMillis();
                                    User u =
                                            new User(
                                                    null,
                                                    phone,
                                                    null,
                                                    "用户" + phone.substring(phone.length() - 4),
                                                    now,
                                                    now);
                                    return users.save(u);
                                }))
                .flatMap(u -> createSession(u).map(token -> new AuthResult(token, toDto(u))));
    }

    /** 创建微信扫码票据。 */
    public Mono<WechatTicketView> createTicket() {
        String ticket = uuid();
        long now = System.currentTimeMillis();
        WechatTicket t =
                new WechatTicket(ticket, "PENDING", null, null, null, now, now + TICKET_TTL);
        return tickets.save(t).map(x -> new WechatTicketView(ticket));
    }

    /** 查询微信票据状态；已确认则附带 token。 */
    public Mono<TicketStatus> getStatus(String ticket) {
        return tickets.findById(ticket)
                .flatMap(
                        t -> {
                            if (t.expiresAt() < System.currentTimeMillis()) {
                                return Mono.just(new TicketStatus("EXPIRED", null, null));
                            }
                            if (!"CONFIRMED".equals(t.status()) || t.token() == null) {
                                return Mono.just(new TicketStatus(t.status(), null, null));
                            }
                            return users.findById(t.userId())
                                    .map(u -> new TicketStatus("CONFIRMED", t.token(), toDto(u)));
                        })
                .switchIfEmpty(Mono.just(new TicketStatus("UNKNOWN", null, null)));
    }

    /**
     * 演示用扫码确认。默认保留以兼容本地 demo；生产通过 AGENT_WECHAT_DEMO_CONFIRM=false 关闭， 改用带签名的
     * /api/auth/wechat/callback。
     */
    public Mono<AuthResult> confirmScan(String ticket) {
        if (!wechatCfg().demoConfirm()) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Demo WeChat login is disabled"));
        }
        return confirmScanWithOpenId(ticket, "sim_" + ticket);
    }

    /** 真实/已验签的微信确认入口：openId 由受信任的回调提供，不再由前端伪造。 */
    public Mono<AuthResult> confirmScanWithOpenId(String ticket, String openId) {
        if (openId == null || openId.isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "openId is required"));
        }
        return tickets.findById(ticket)
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码不存在")))
                .flatMap(
                        t -> {
                            if (t.expiresAt() < System.currentTimeMillis()) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, "二维码已失效，请刷新"));
                            }
                            if ("CONFIRMED".equals(t.status()) && !openId.equals(t.openId())) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.FORBIDDEN, "票据已被其他账号确认"));
                            }
                            return users.findByWechatOpenId(openId)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () -> {
                                                        long now = System.currentTimeMillis();
                                                        User u =
                                                                new User(
                                                                        null, null, openId, "微信用户",
                                                                        now, now);
                                                        return users.save(u);
                                                    }))
                                    .flatMap(
                                            u ->
                                                    createSession(u)
                                                            .flatMap(
                                                                    token -> {
                                                                        WechatTicket updated =
                                                                                new WechatTicket(
                                                                                        t.ticket(),
                                                                                        "CONFIRMED",
                                                                                        openId,
                                                                                        u.id(),
                                                                                        token,
                                                                                        t
                                                                                                .createdAt(),
                                                                                        t
                                                                                                .expiresAt());
                                                                        return tickets.save(updated)
                                                                                .thenReturn(
                                                                                        new AuthResult(
                                                                                                token,
                                                                                                toDto(
                                                                                                        u)));
                                                                    }));
                        });
    }

    /** 取出会话绑定的 CSRF token；旧会话（无该字段）返回 empty，需重新登录。 */
    public Mono<String> csrfTokenFor(String token) {
        if (token == null) {
            return Mono.empty();
        }
        return sessions.findById(token)
                .filter(s -> s.expiresAt() >= System.currentTimeMillis())
                .map(LoginSession::csrfToken);
    }

    /** 校验 token，返回 userId；失效返回 empty。 */
    public Mono<String> validateToken(String token) {
        if (token == null) return Mono.empty();
        return sessions.findById(token)
                .flatMap(
                        s ->
                                s.expiresAt() < System.currentTimeMillis()
                                        ? Mono.empty()
                                        : Mono.just(s.userId()));
    }

    public Mono<UserDto> me(String token) {
        return validateToken(token)
                .flatMap(users::findById)
                .map(this::toDto)
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效")));
    }

    public Mono<Void> logout(String token) {
        if (token == null) return Mono.empty();
        return sessions.findById(token).flatMap(sessions::delete).then();
    }

    private Mono<String> createSession(User u) {
        String token = uuid();
        String csrfToken = uuid();
        long now = System.currentTimeMillis();
        return sessions.save(new LoginSession(token, u.id(), now, now + SESSION_TTL, csrfToken))
                .map(x -> token);
    }

    private SmsCode latest(List<SmsCode> codes) {
        return codes.stream().max(Comparator.comparingLong(SmsCode::createdAt)).orElse(null);
    }

    private AuthProperties.Sms smsCfg() {
        AuthProperties.Sms cfg = properties.sms();
        if (cfg == null) {
            return new AuthProperties.Sms("demo", "", "", Duration.ofSeconds(60), 5, 5);
        }
        return cfg;
    }

    private AuthProperties.Wechat wechatCfg() {
        AuthProperties.Wechat cfg = properties.wechat();
        if (cfg == null) {
            return new AuthProperties.Wechat(true, "", Duration.ofMinutes(5));
        }
        return cfg;
    }

    private UserDto toDto(User u) {
        return new UserDto(u.id(), u.phone(), u.nickname());
    }

    private boolean isValidPhone(String p) {
        return p != null && p.matches("^1[3-9]\\d{9}$");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ===== DTO =====
    public record AuthResult(String token, UserDto user) {}

    public record UserDto(String id, String phone, String nickname) {}

    public record WechatTicketView(String ticket) {}

    public record TicketStatus(String status, String token, UserDto user) {}
}
