package dev.conduit.server.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceMapper;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.StandardJsonDecoder;
import dev.conduit.server.product.ProductDtos.ProductView;
import dev.conduit.server.product.ProductDtos.SaveProductRequest;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.thingmodel.ProductPropertyMapper;
import dev.conduit.server.thingmodel.ThingModelCache;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class ProductService {

    private final ProductMapper products;
    private final DeviceMapper devices;
    private final ProductPropertyMapper properties;
    private final DecoderRegistry decoders;
    private final ThingModelCache thingModels;
    private final KeyGenerator keys;
    private final Clock clock;

    public ProductService(ProductMapper products, DeviceMapper devices, ProductPropertyMapper properties,
                          DecoderRegistry decoders, ThingModelCache thingModels, KeyGenerator keys, Clock clock) {
        this.products = products;
        this.devices = devices;
        this.properties = properties;
        this.decoders = decoders;
        this.thingModels = thingModels;
        this.keys = keys;
        this.clock = clock;
    }

    public ProductView create(SaveProductRequest request) {
        Product p = new Product();
        p.setProductKey(keys.productKey());
        p.setName(request.name());
        p.setDescription(request.description());
        p.setCodec(requireCodec(request.codec() == null ? StandardJsonDecoder.NAME : request.codec()));
        products.insert(p);
        return ProductView.of(products.selectById(p.getId()), 0);
    }

    public PageView<ProductView> list(int page, int size) {
        Page<Product> result = products.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Product>().orderByDesc(Product::getId));
        Map<Long, Integer> counts = properties.countByProducts(
                result.getRecords().stream().map(Product::getId).toList());
        return new PageView<>(result.getRecords().stream()
                .map(p -> ProductView.of(p, counts.getOrDefault(p.getId(), 0))).toList(),
                result.getTotal(), page, size);
    }

    public ProductView get(String productKey) {
        Product p = require(productKey);
        return ProductView.of(p, (int) properties.countByProduct(p.getId()));
    }

    public ProductView update(String productKey, SaveProductRequest request) {
        Product p = require(productKey);
        p.setName(request.name());
        p.setDescription(request.description());
        if (request.codec() != null) {
            p.setCodec(requireCodec(request.codec()));
        }
        p.setUpdatedAt(OffsetDateTime.now(clock));
        products.updateById(p);
        thingModels.evict(productKey);
        return ProductView.of(p, (int) properties.countByProduct(p.getId()));
    }

    public void delete(String productKey) {
        Product p = require(productKey);
        boolean hasDevices = devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getProductId, p.getId()));
        if (hasDevices) {
            throw ApiException.conflict("产品下仍有设备，不能删除");
        }
        products.deleteById(p.getId());
        thingModels.evict(productKey);
    }

    public Product require(String productKey) {
        Product p = products.selectOne(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (p == null) {
            throw ApiException.notFound("产品不存在: " + productKey);
        }
        return p;
    }

    private String requireCodec(String codec) {
        if (!decoders.exists(codec)) {
            throw ApiException.badRequest("解码器不存在: " + codec);
        }
        return codec;
    }
}
