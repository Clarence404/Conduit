package dev.conduit.server.tenant;

import dev.conduit.server.web.ApiException;

import java.util.regex.Pattern;

/** 租户编码规则：^[a-z][a-z0-9-]{1,31}$（2～32 位，小写字母开头），全局唯一，创建后不可修改。数据库也有同样的约束。 */
public final class TenantCodePolicy {

    static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");

    private TenantCodePolicy() {
    }

    /**
     * 校验租户编码。
     *
     * @return 原样返回合法的编码
     * @throws ApiException 400：为 null 或不符合规则
     */
    public static String requireValid(String code) {
        if (code == null || !PATTERN.matcher(code).matches()) {
            throw ApiException.badRequest("租户编码须为 2～32 位，小写字母开头，只能包含小写字母、数字和 -");
        }
        return code;
    }
}
