package dev.conduit.server.thingmodel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按 productKey 缓存 ProductProfile：写入后 60 秒过期，最多 10 000 项。跨租户查询。
 * 产品不存在时不缓存（由设备身份检查兜底）。修改属性、导入、修改 codec、删除产品时由服务层主动 evict；
 * 多实例下其他实例最多延迟 60 秒。读库异常原样抛出，流水线按可恢复错误原地重试。
 */
@Component
public class ThingModelCache {

    static final Duration TTL = Duration.ofSeconds(60);
    static final long MAX_SIZE = 10_000;

    private final ProductMapper products;
    private final ProductPropertyMapper properties;
    private final Cache<String, ProductProfile> cache;

    @Autowired
    public ThingModelCache(ProductMapper products, ProductPropertyMapper properties) {
        this(products, properties, Ticker.systemTicker());
    }

    ThingModelCache(ProductMapper products, ProductPropertyMapper properties, Ticker ticker) {
        this.products = products;
        this.properties = properties;
        this.cache = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(MAX_SIZE).ticker(ticker).build();
    }

    public Optional<ProductProfile> find(String productKey) {
        return Optional.ofNullable(cache.get(productKey, this::load));
    }

    public void evict(String productKey) {
        cache.invalidate(productKey);
    }

    private ProductProfile load(String productKey) {
        Product product = products.findByKeyAnyTenant(productKey);
        if (product == null) {
            return null;
        }
        List<ProductProperty> rows = TenantContext.callAs(product.getTenantId(), () -> properties.selectList(
                new LambdaQueryWrapper<ProductProperty>()
                        .eq(ProductProperty::getProductId, product.getId())
                        .orderByAsc(ProductProperty::getId)));
        Map<String, PropertySpec> specs = new LinkedHashMap<>();
        rows.forEach(row -> specs.put(row.getIdentifier(), PropertySpec.of(row)));
        return new ProductProfile(product.getCodec(), Collections.unmodifiableMap(specs));
    }
}
