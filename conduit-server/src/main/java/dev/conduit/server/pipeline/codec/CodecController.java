package dev.conduit.server.pipeline.codec;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 编解码器列表的 REST 入口（GET /api/v1/codecs），供 UI 创建/编辑产品时的 codec 下拉框使用，
 * 数据来自 {@link DecoderRegistry} 中已注册的 PayloadDecoder。
 */
@RestController
public class CodecController {

    /** 下拉框条目：name 为产品存储的 codec 值，displayName 为界面显示名。 */
    public record CodecView(String name, String displayName) {
    }

    private final DecoderRegistry registry;

    public CodecController(DecoderRegistry registry) {
        this.registry = registry;
    }

    /** 列出全部可用解码器，按 name 升序（由 DecoderRegistry 保证顺序）。 */
    @GetMapping("/api/v1/codecs")
    public List<CodecView> list() {
        return registry.all().stream().map(d -> new CodecView(d.name(), d.displayName())).toList();
    }
}
