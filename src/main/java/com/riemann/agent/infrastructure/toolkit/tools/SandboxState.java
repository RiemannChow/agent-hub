package com.riemann.agent.infrastructure.toolkit.tools;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 本轮对话（turn）内共享的 SeekDB COW 沙箱状态持有者。
 *
 * <p><b>为什么需要可变的持有者（而非把沙箱名塞进 {@link AgentCallContext} 这个 record）：</b> AgentScope 在每轮开始时把 {@link
 * AgentCallContext} 作为值对象一次性放进 {@code RuntimeContext}， 之后每个工具的 {@code AgentCallContext} 参数都从同一个
 * {@code RuntimeContext} 按类型取出—— 是「同一份引用」。但 {@code AgentCallContext} 是 record（不可变），{@code
 * withSandbox} 返回的是 <b>新对象</b>，fork 工具改了它、放回去的对象谁也不持有，后续 write 工具拿到的仍是那份空白原始值， 导致「fork 后写不进沙箱」。
 *
 * <p>因此这里用一个<b>可变持有者</b>放进 {@code RuntimeContext}：fork 写入、write/commit/discard 读出，
 * 因为持有者引用整轮共享，写入对同轮所有工具可见。它本身是线程安全的 （{@link AtomicReference}），避免开启并行工具时读写竞态。
 *
 * <p><b>已知限制（issue #16 后续项）：</b>状态只存活于本轮 {@code RuntimeContext}， 不跨请求、不跨实例共享。多实例部署时 fork / write /
 * commit / discard 必须落在同一实例； 需要跨实例时把本类替换为 Redis / DB 支持的状态存储。本次修复（#16 PR）只处理写入隔离 与合并事务，不扩大到状态外置。
 */
public final class SandboxState {

    /** 当前活跃沙箱库名；为空表示本轮未 fork。 */
    private final AtomicReference<String> activeSandbox = new AtomicReference<>("");

    /** 返回当前活跃沙箱名（空串表示未启用）。 */
    public String activeSandbox() {
        return activeSandbox.get();
    }

    /** 本轮是否已 fork 出沙箱。 */
    public boolean isActive() {
        String s = activeSandbox.get();
        return s != null && !s.isBlank();
    }

    /** fork 成功后记录沙箱名。 */
    public void activate(String sandboxDbName) {
        activeSandbox.set(sandboxDbName == null ? "" : sandboxDbName);
    }

    /** 提交/丢弃后清空（沙箱已不再活跃）。 */
    public void clear() {
        activeSandbox.set("");
    }
}
