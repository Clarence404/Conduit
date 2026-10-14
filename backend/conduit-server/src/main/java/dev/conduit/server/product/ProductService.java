package dev.conduit.server.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceMapper;
import dev.conduit.server.product.ProductDtos.ProductView;
import dev.conduit.server.product.ProductDtos.SaveProductRequest;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class ProductService {

    private final ProductMapper products;
    private final DeviceMapper devices;
    private final KeyGenerator keys;
    private final Clock clock;

    public ProductService(ProductMapper products, DeviceMapper devices, KeyGenerator keys, Clock clock) {
        this.products = products;
        this.devices = devices;
        this.keys = keys;
        this.clock = clock;
    }

    public ProductView create(SaveProductRequest request) {
        Product p = new Product();
        p.setProductKey(keys.productKey());
        p.setName(request.name());
        p.setDescription(request.description());
        products.insert(p);
        return ProductView.of(products.selectById(p.getId()));
    }

    public PageView<ProductView> list(int page, int size) {
        Page<Product> result = products.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Product>().orderByDesc(Product::getId));
        return new PageView<>(result.getRecords().stream().map(ProductView::of).toList(),
                result.getTotal(), page, size);
    }

    public ProductView get(String productKey) {
        return ProductView.of(require(productKey));
    }

    public ProductView update(String productKey, SaveProductRequest request) {
        Product p = require(productKey);
        p.setName(request.name());
        p.setDescription(request.description());
        p.setUpdatedAt(OffsetDateTime.now(clock));
        products.updateById(p);
        return ProductView.of(p);
    }

    public void delete(String productKey) {
        Product p = require(productKey);
        boolean hasDevices = devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getProductId, p.getId()));
        if (hasDevices) {
            throw ApiException.conflict("产品下仍有设备，不能删除");
        }
        products.deleteById(p.getId());
    }

    public Product require(String productKey) {
        Product p = products.selectOne(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (p == null) {
            throw ApiException.notFound("产品不存在: " + productKey);
        }
        return p;
    }
}
