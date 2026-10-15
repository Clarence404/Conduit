package dev.conduit.server.pipeline.codec;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 收集所有 PayloadDecoder Bean 并按 name 索引；重名时启动失败。 */
@Component
public class DecoderRegistry {

    private final Map<String, PayloadDecoder> byName;

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

    public Optional<PayloadDecoder> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    public boolean exists(String name) {
        return find(name).isPresent();
    }

    public List<PayloadDecoder> all() {
        return List.copyOf(byName.values());
    }
}
