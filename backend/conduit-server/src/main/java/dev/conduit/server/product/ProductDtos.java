package dev.conduit.server.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record SaveProductRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description) {
    }

    public record ProductView(String productKey, String name, String description, OffsetDateTime createdAt) {

        static ProductView of(Product p) {
            return new ProductView(p.getProductKey(), p.getName(), p.getDescription(), p.getCreatedAt());
        }
    }
}
