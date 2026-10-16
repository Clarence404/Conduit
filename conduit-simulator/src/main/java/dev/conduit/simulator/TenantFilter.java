package dev.conduit.simulator;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 模拟哪些租户（SIM_TENANTS）：codes 为空表示全部启用中的租户，否则只模拟编码精确匹配的租户。
 *
 * @param codes 租户编码集合；空集合表示不限
 */
public record TenantFilter(Set<String> codes) {

    /** 不限租户。 */
    public static final TenantFilter ALL = new TenantFilter(Set.of());

    public TenantFilter {
        codes = Set.copyOf(codes);
    }

    /**
     * 解析 SIM_TENANTS：去掉首尾空白后按逗号分隔；null、空白、* 或列表中含 * 都表示全部租户，空项忽略。
     */
    public static TenantFilter parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String code = part.trim();
            if (code.equals("*")) {
                return ALL;
            }
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? ALL : new TenantFilter(codes);
    }

    /** 是否不限租户。 */
    public boolean all() {
        return codes.isEmpty();
    }

    /** 租户是否在模拟范围内。 */
    public boolean includes(String code) {
        return codes.isEmpty() || codes.contains(code);
    }
}
