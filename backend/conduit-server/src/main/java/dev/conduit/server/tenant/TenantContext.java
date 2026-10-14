package dev.conduit.server.tenant;

import java.util.function.Supplier;

/** 当前线程绑定的租户。HTTP 请求由 JwtAuthFilter 绑定，后台任务用 runAs/callAs 显式绑定。 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No tenant bound to current thread");
        }
        return id;
    }

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

    public static void runAs(long tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
