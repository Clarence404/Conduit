package dev.conduit.server.auth;

import dev.conduit.server.web.ApiException;

/** 显示名称规则：去掉首尾空白，空白视为未设置（界面显示用户名），最多 64 个字符。 */
public final class DisplayNamePolicy {

    static final int MAX_LENGTH = 64;

    private DisplayNamePolicy() {
    }

    /**
     * 规范化显示名称。
     *
     * @return 去掉首尾空白后的名称；null 或空白返回 null
     * @throws ApiException 400：超过 64 个字符
     */
    public static String normalize(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw ApiException.badRequest("显示名称最多 64 个字符");
        }
        return trimmed;
    }
}
