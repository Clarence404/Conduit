package dev.conduit.server.pipeline.codec;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 解码器注册表：收集所有 PayloadDecoder Bean 并按 name 索引；重名时启动失败。
 * 流水线按产品的 codec 值查找解码器，REST 层用它列出可选 codec 并校验产品所填的 codec。
 */
@Component
public class DecoderRegistry {

    private final Map<String, PayloadDecoder> byName;

    /** @throws IllegalStateException 两个解码器 name 相同时，使应用启动失败 */
    public DecoderRegistry(List<PayloadDecoder> decoders) {
        Map<String, PayloadDecoder> map = new TreeMap<>();
        for (PayloadDecoder decoder : decoders) {
            PayloadDecoder previous = map.putIfAbsent(decoder.name(), decoder);
            if (previous != null) {
                throw new IllegalStateException("Duplicate payload decoder name '" + decoder.name() + "': "
                        + previous.getClass().getName() + " and " + decoder.getClass().getName());
            }
        }
        this.byName = Collections.unmodifiableMap(map);
    }

    /** 按 name 查找解码器；name 为 null 或未注册时返回空。 */
    public Optional<PayloadDecoder> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    public boolean exists(String name) {
        return find(name).isPresent();
    }

    /** 全部解码器的不可变快照，按 name 升序。 */
    public List<PayloadDecoder> all() {
        return List.copyOf(byName.values());
    }
}
