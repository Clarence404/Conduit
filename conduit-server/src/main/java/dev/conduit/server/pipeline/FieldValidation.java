package dev.conduit.server.pipeline;

import java.util.List;
import java.util.Map;

/** accepted 中的值已是写入类型：Double / Long / Boolean / String。 */
public record FieldValidation(Map<String, Object> accepted, List<RejectedField> rejected) {
}
