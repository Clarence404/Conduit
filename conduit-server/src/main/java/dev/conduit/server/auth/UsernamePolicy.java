package dev.conduit.server.auth;

import dev.conduit.server.web.ApiException;

import java.util.regex.Pattern;

/** 用户名规则：3～32 位，字母开头，只能含字母、数字、_、-、.。全平台唯一由数据库唯一键与服务层检查保证。 */
public final class UsernamePolicy {

    static final Pattern PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{2,31}$");

    private UsernamePolicy() {
    }

    /**
     * 校验用户名。
     *
     * @return 原样返回合法的用户名
     * @throws ApiException 400：为 null 或不符合规则
     */
    public static String requireValid(String username) {
        if (username == null || !PATTERN.matcher(username).matches()) {
            throw ApiException.badRequest("用户名须为 3～32 位，字母开头，只能包含字母、数字、_、-、.");
        }
        return username;
    }
}
