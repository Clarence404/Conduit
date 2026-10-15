package dev.conduit.server.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceMapper;
import dev.conduit.server.device.DeviceStats;
import dev.conduit.server.device.DeviceStats.ProductCounts;
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
import java.util.List;
import java.util.Map;

/**
 * 产品的增删改查。产品属于当前租户（租户过滤由拦截器完成）；响应带属性数与设备数（在线/总数，见 DeviceStats）；
 * 物模型缓存在产品变更、删除时同步清除。
 */
@Service
public class ProductService {

    private static final ProductCounts NO_DEVICES = new ProductCounts(0, 0, 0);

    private final ProductMapper products;
    private final DeviceMapper devices;
    private final ProductPropertyMapper properties;
    private final DeviceStats stats;
    private final DecoderRegistry decoders;
    private final ThingModelCache thingModels;
    private final KeyGenerator keys;
    private final Clock clock;

    public ProductService(ProductMapper products, DeviceMapper devices, ProductPropertyMapper properties,
                          DeviceStats stats, DecoderRegistry decoders, ThingModelCache thingModels, KeyGenerator keys,
                          Clock clock) {
        this.products = products;
        this.devices = devices;
        this.properties = properties;
        this.stats = stats;
        this.decoders = decoders;
        this.thingModels = thingModels;
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * 新建产品，productKey 由系统生成。codec 缺省为 standard-json；指定的解码器不存在抛 400。新产品没有设备，计数直接为 0。
     */
    public ProductView create(SaveProductRequest request) {
        Product p = new Product();
        p.setProductKey(keys.productKey());
        p.setName(request.name());
        p.setDescription(request.description());
        p.setCodec(requireCodec(request.codec() == null ? StandardJsonDecoder.NAME : request.codec()));
        products.insert(p);
        return ProductView.of(products.selectById(p.getId()), 0, NO_DEVICES);
    }

    /**
     * 分页列出当前租户的产品，按 id 倒序（新的在前）。属性数与设备计数都对本页产品各做一次批量查询，避免逐个产品查询。page 从 1 开始。
     */
    public PageView<ProductView> list(int page, int size) {
        Page<Product> result = products.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Product>().orderByDesc(Product::getId));
        List<Long> ids = result.getRecords().stream().map(Product::getId).toList();
        Map<Long, Integer> counts = properties.countByProducts(ids);
        DeviceStats.Snapshot snapshot = stats.snapshot(ids);
        return new PageView<>(result.getRecords().stream()
                .map(p -> ProductView.of(p, counts.getOrDefault(p.getId(), 0), snapshot.of(p.getId()))).toList(),
                result.getTotal(), page, size);
    }

    /**
     * 查询单个产品（含属性数与设备计数），不存在抛 404。
     */
    public ProductView get(String productKey) {
        return view(require(productKey));
    }

    /**
     * 修改名称、描述（null 表示清空）；codec 为 null 时保持不变，不存在的解码器抛 400。修改后清除物模型缓存（缓存中含 codec）。
     */
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
        return view(p);
    }

    /**
     * 删除产品。产品下仍有设备抛 409；成功后清除物模型缓存。
     */
    public void delete(String productKey) {
        Product p = require(productKey);
        boolean hasDevices = devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getProductId, p.getId()));
        if (hasDevices) {
            throw ApiException.conflict("产品下仍有设备，不能删除");
        }
        products.deleteById(p.getId());
        thingModels.evict(productKey);
    }

    /**
     * 按 productKey 取当前租户的产品实体，不存在（含属于其他租户）抛 404。供其他服务复用。
     */
    public Product require(String productKey) {
        Product p = products.selectOne(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (p == null) {
            throw ApiException.notFound("产品不存在: " + productKey);
        }
        return p;
    }

    /** 单个产品的响应视图：属性数与设备计数各查一次。 */
    private ProductView view(Product p) {
        return ProductView.of(p, (int) properties.countByProduct(p.getId()),
                stats.snapshot(List.of(p.getId())).of(p.getId()));
    }

    /**
     * 校验解码器已注册，否则抛 400；返回原值。
     */
    private String requireCodec(String codec) {
        if (!decoders.exists(codec)) {
            throw ApiException.badRequest("解码器不存在: " + codec);
        }
        return codec;
    }
}
