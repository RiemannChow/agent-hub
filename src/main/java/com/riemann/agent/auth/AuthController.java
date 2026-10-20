package com.riemann.agent.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record SmsSendReq(String phone) {}
    public record SmsSendResp(String code, boolean demo) {}
    public record SmsVerifyReq(String phone, String code) {}

    /** 发送验证码（演示环境直接返回 code）。 */
    @PostMapping("/sms/send")
    public Mono<SmsSendResp> send(@RequestBody SmsSendReq req) {
        return auth.sendSmsCode(req.phone()).map(code -> new SmsSendResp(code, true));
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

    /** 模拟微信扫码确认。 */
    @PostMapping("/wechat/confirm")
    public Mono<AuthService.AuthResult> confirm(@RequestParam String ticket) {
        return auth.confirmScan(ticket);
    }

    /** 获取当前登录用户。 */
    @GetMapping("/me")
    public Mono<AuthService.UserDto> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = tokenOf(authHeader);
        if (token == null) return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
        return auth.me(token);
    }

    /** 退出登录。 */
    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = tokenOf(authHeader);
        if (token == null) return Mono.just(Map.of("ok", true));
        return auth.logout(token).thenReturn(Map.of("ok", true));
    }

    private String tokenOf(String header) {
        if (header == null) return null;
        if (header.startsWith("Bearer ")) return header.substring(7);
        return header;
    }
}
