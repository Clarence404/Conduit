package dev.conduit.server.thingmodel;

import java.util.Map;

/** 流水线需要的产品配置：解码器名称 + 属性（按标识索引；为空表示没有物模型）。 */
public record ProductProfile(String codec, Map<String, PropertySpec> properties) {

    public boolean hasModel() {
        return !properties.isEmpty();
    }
}
