package dev.conduit.server.pipeline.codec;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CodecController {

    public record CodecView(String name, String displayName) {
    }

    private final DecoderRegistry registry;

    public CodecController(DecoderRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/v1/codecs")
    public List<CodecView> list() {
        return registry.all().stream().map(d -> new CodecView(d.name(), d.displayName())).toList();
    }
}
