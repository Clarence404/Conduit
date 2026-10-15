package dev.conduit.server.thingmodel;

import dev.conduit.server.web.ApiException;

/**
 * 物模型导入模式：决定文件中没有的已有属性如何处理。
 */
public enum ImportMode {
    /** 新增或更新文件中的属性，保留其他。 */
    MERGE,
    /** 以文件为准，删除文件中没有的属性。 */
    REPLACE;

    /**
     * 解析请求参数 mode（不区分大小写）；不是 merge/replace 时抛 400。
     */
    static ImportMode parse(String value) {
        if ("merge".equalsIgnoreCase(value)) {
            return MERGE;
        }
        if ("replace".equalsIgnoreCase(value)) {
            return REPLACE;
        }
        throw ApiException.badRequest("mode 必须是 merge 或 replace");
    }
}
