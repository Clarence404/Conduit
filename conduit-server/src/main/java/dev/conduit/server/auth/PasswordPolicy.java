package dev.conduit.server.auth;

import dev.conduit.server.web.ApiException;

/**
 * 密码规则：8～64 位，同时包含（ASCII）字母和数字。上限 64 让常见输入落在 BCrypt 只取前 72 字节的范围内。
 * 只用于通过接口设置的新密码；启动时由环境变量给出的初始密码不受此限制。
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
    }

    /**
     * 校验新密码。
     *
     * @return 原样返回合法的密码
     * @throws ApiException 400：为 null、长度不在 8～64、缺少字母或缺少数字
     */
    public static String requireValid(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH
                || password.chars().noneMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
                || password.chars().noneMatch(c -> c >= '0' && c <= '9')) {
            throw ApiException.badRequest("密码须为 8～64 位，且同时包含字母和数字");
        }
        return password;
    }
}
