package dev.conduit.server.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 产品接口的请求与响应对象集合。
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /** codec 可选：新建时缺省为 standard-json，修改时缺省保持不变。 */
    public record SaveProductRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description,
                                     @Size(max = 32) String codec) {
    }

    /**
     * 产品响应视图。propertyCount 为物模型属性数。
     */
    public record ProductView(String productKey, String name, String description, String codec, int propertyCount,
                              OffsetDateTime createdAt) {

        static ProductView of(Product p, int propertyCount) {
            return new ProductView(p.getProductKey(), p.getName(), p.getDescription(), p.getCodec(), propertyCount,
                    p.getCreatedAt());
        }
    }
}
