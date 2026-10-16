package dev.conduit.e2e;

/** 渲染 deploy/dev/emqx/base.hocon.tpl（与开发环境包 compose 中 EMQX 容器启动时的 sed 替换相同的两个占位符）。 */
final class EmqxConfig {

    static final String AUTH_URL = "__CONDUIT_AUTH_URL__";
    static final String INTERNAL_TOKEN = "__CONDUIT_INTERNAL_TOKEN__";

    private EmqxConfig() {
    }

    /**
     * 替换鉴权回调地址与内部 token。
     *
     * @throws IllegalArgumentException 模板缺少任一占位符（模板被改动，e2e 不能再假定其结构）
     */
    static String render(String template, String authUrl, String internalToken) {
        if (!template.contains(AUTH_URL) || !template.contains(INTERNAL_TOKEN)) {
            throw new IllegalArgumentException("EMQX template must contain " + AUTH_URL + " and " + INTERNAL_TOKEN);
        }
        return template.replace(AUTH_URL, authUrl).replace(INTERNAL_TOKEN, internalToken);
    }
}
