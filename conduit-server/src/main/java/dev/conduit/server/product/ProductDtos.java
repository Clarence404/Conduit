package dev.conduit.server.product;

import dev.conduit.server.device.DeviceStats.ProductCounts;
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
     * 产品响应视图。
     *
     * @param propertyCount 物模型属性数
     * @param deviceCount   产品下的设备数
     * @param onlineCount   产品下的在线设备数；Redis 不可用时为 null
     */
    public record ProductView(String productKey, String name, String description, String codec, int propertyCount,
                              int deviceCount, Integer onlineCount, OffsetDateTime createdAt) {

        /** 由实体、属性数与设备计数组装视图。 */
        static ProductView of(Product p, int propertyCount, ProductCounts counts) {
            return new ProductView(p.getProductKey(), p.getName(), p.getDescription(), p.getCodec(), propertyCount,
                    counts.deviceCount(), counts.onlineCount(), p.getCreatedAt());
        }
    }
}
