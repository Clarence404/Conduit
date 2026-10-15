package dev.conduit.server.web;

import java.util.List;

/** 批量校验失败：返回 400，ProblemDetail 带 errors:[{identifier, message}] 列出全部错误。 */
public class ItemErrorsException extends RuntimeException {

    public record ItemError(String identifier, String message) {
    }

    private final List<ItemError> errors;

    public ItemErrorsException(String detail, List<ItemError> errors) {
        super(detail);
        this.errors = List.copyOf(errors);
    }

    public List<ItemError> errors() {
        return errors;
    }
}
