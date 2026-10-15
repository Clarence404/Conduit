package dev.conduit.server.tenant;

import java.util.function.Supplier;

/** 当前线程绑定的租户。HTTP 请求由 JwtAuthFilter 绑定，后台任务用 runAs/callAs 显式绑定。 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 绑定当前线程的租户；请求结束或任务完成后必须 {@link #clear()}。 */
    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    /** 当前线程绑定的租户 id；未绑定返回 null。 */
    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 取当前租户 id；未绑定时抛 IllegalStateException（租户拦截器据此拒绝无租户上下文的查询）。 */
    public static long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No tenant bound to current thread");
        }
        return id;
    }

    /**
     * 以指定租户执行并返回结果，结束后恢复调用前的绑定（支持嵌套，原先未绑定则清除）。
     */
    public static <T> T callAs(long tenantId, Supplier<T> action) {
        Long previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 无返回值版本的 {@link #callAs}。 */
    public static void runAs(long tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
