package com.riemann.agent.auth;

import java.util.Map;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final AuthProperties properties;

    public AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    public record SmsSendReq(String phone) {}

    public record SmsSendResp(String code, boolean demo) {}

    public record SmsVerifyReq(String phone, String code) {}

    public record WechatCallbackReq(
            String ticket, String openId, String timestamp, String signature) {}

    /** 发送验证码：仅 demo 模式在响应中回传 code，生产模式由短信网关下发。 */
    @PostMapping("/sms/send")
    public Mono<SmsSendResp> send(@RequestBody SmsSendReq req) {
        boolean demo = "demo".equalsIgnoreCase(demoMode());
        return auth.sendSmsCode(req.phone()).map(code -> new SmsSendResp(demo ? code : null, demo));
    }

    /** 手机号 + 验证码登录。 */
    @PostMapping("/sms/verify")
    public Mono<AuthService.AuthResult> verify(@RequestBody SmsVerifyReq req) {
        return auth.loginByPhone(req.phone(), req.code());
    }

    /** 创建微信扫码票据。 */
    @PostMapping("/wechat/ticket")
    public Mono<AuthService.WechatTicketView> ticket() {
        return auth.createTicket();
    }

    /** 轮询微信票据状态。 */
    @GetMapping("/wechat/status")
    public Mono<AuthService.TicketStatus> status(@RequestParam String ticket) {
        return auth.getStatus(ticket);
    }

    /** 演示扫码确认；AGENT_WECHAT_DEMO_CONFIRM=false 时返回 404。 */
    @PostMapping("/wechat/confirm")
    public Mono<AuthService.AuthResult> confirm(@RequestParam String ticket) {
        return auth.confirmScan(ticket);
    }

    /** 真实微信回调：必须携带 HMAC 签名且在时间窗内。 */
    @PostMapping("/wechat/callback")
    public Mono<AuthService.AuthResult> callback(@RequestBody WechatCallbackReq req) {
        AuthProperties.Wechat cfg = properties.wechat();
        if (cfg == null || cfg.callbackToken() == null || cfg.callbackToken().isBlank()) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "WeChat callback is not configured"));
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(req.timestamp());
        } catch (RuntimeException e) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp"));
        }
        long skew = cfg.allowedSkew() == null ? 300_000L : cfg.allowedSkew().toMillis();
        if (Math.abs(System.currentTimeMillis() - timestamp) > skew) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stale callback"));
        }
        if (!WechatSignature.verify(
                cfg.callbackToken(),
                req.ticket(),
                req.openId(),
                req.timestamp(),
                req.signature())) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid signature"));
        }
        return auth.confirmScanWithOpenId(req.ticket(), req.openId());
    }

    /** 供 Cookie 认证的前端读取 CSRF token（Bearer 客户端无需调用）。 */
    @GetMapping("/csrf")
    public Mono<Map<String, Object>> csrf(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            ServerWebExchange exchange) {
        String token = tokenOf(authHeader);
        if (token == null) {
            HttpCookie cookie = exchange.getRequest().getCookies().getFirst("ah_token");
            token = cookie == null ? null : cookie.getValue();
        }
        if (token == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
        }
        return auth.csrfTokenFor(token)
                .map(t -> Map.<String, Object>of("csrfToken", t))
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效")));
    }

    /** 获取当前登录用户。 */
    @GetMapping("/me")
    public Mono<AuthService.UserDto> me(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = tokenOf(authHeader);
        if (token == null)
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
        return auth.me(token);
    }

    /** 退出登录。 */
    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = tokenOf(authHeader);
        if (token == null) return Mono.just(Map.of("ok", true));
        return auth.logout(token).thenReturn(Map.of("ok", true));
    }

    private String demoMode() {
        AuthProperties.Sms cfg = properties.sms();
        return cfg == null || cfg.mode() == null ? "demo" : cfg.mode();
    }

    private String tokenOf(String header) {
        if (header == null) return null;
        if (header.startsWith("Bearer ")) return header.substring(7);
        return header;
    }
}
