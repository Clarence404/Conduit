package dev.conduit.server.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class ProductDtos {

    private ProductDtos() {
    }

    /** codec 可选：新建时缺省为 standard-json，修改时缺省保持不变。 */
    public record SaveProductRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description,
                                     @Size(max = 32) String codec) {
    }

    public record ProductView(String productKey, String name, String description, String codec, int propertyCount,
                              OffsetDateTime createdAt) {

        static ProductView of(Product p, int propertyCount) {
            return new ProductView(p.getProductKey(), p.getName(), p.getDescription(), p.getCodec(), propertyCount,
                    p.getCreatedAt());
        }
    }
}
