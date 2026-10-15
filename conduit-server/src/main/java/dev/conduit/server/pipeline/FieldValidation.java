package dev.conduit.server.pipeline;

import java.util.List;
import java.util.Map;

/**
 * {@link FieldValidator} 的校验结果。
 *
 * @param accepted 通过校验的测点，值已是写入类型：Double / Long / Boolean / String，保持原顺序
 * @param rejected 被剔除的测点及原因；这些测点不会写入，只作为 FIELD_REJECTED 死信记录留痕
 */
public record FieldValidation(Map<String, Object> accepted, List<RejectedField> rejected) {
}
