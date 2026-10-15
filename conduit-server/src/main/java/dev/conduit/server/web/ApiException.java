package dev.conduit.server.web;

import org.springframework.http.HttpStatus;

/**
 * 业务层抛出的、需要映射为特定 HTTP 状态码的异常，由 {@link GlobalExceptionHandler} 转成 problem+json。
 * 优先使用 badRequest / unauthorized / notFound / conflict 工厂方法。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }
}
