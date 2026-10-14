package dev.conduit.server.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.device.DeviceDtos.CreateDeviceRequest;
import dev.conduit.server.device.DeviceDtos.DeviceCreated;
import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.device.DeviceDtos.SecretView;
import dev.conduit.server.device.DeviceDtos.UpdateDeviceRequest;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.product.ProductService;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.security.SecretHasher;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceMapper devices;
    private final ProductMapper products;
    private final ProductService productService;
    private final KeyGenerator keys;
    private final SecretHasher hasher;
    private final DeviceIdentityCache identityCache;
    private final LatestValueStore latest;
    private final PresenceStore presence;
    private final Clock clock;

    public DeviceService(DeviceMapper devices, ProductMapper products, ProductService productService,
                         KeyGenerator keys, SecretHasher hasher, DeviceIdentityCache identityCache,
                         LatestValueStore latest, PresenceStore presence, Clock clock) {
        this.devices = devices;
        this.products = products;
        this.productService = productService;
        this.keys = keys;
        this.hasher = hasher;
        this.identityCache = identityCache;
        this.latest = latest;
        this.presence = presence;
        this.clock = clock;
    }

    public DeviceCreated create(CreateDeviceRequest request) {
        Product product = productService.require(request.productKey());
        String secret = keys.deviceSecret();
        String salt = hasher.newSalt();

        Device d = new Device();
        d.setProductId(product.getId());
        d.setDeviceKey(keys.deviceKey());
        d.setName(request.name());
        d.setSecretSalt(salt);
        d.setSecretHash(hasher.hash(secret, salt));
        d.setStatus(Device.ENABLED);
        devices.insert(d);

        Device saved = devices.selectById(d.getId());
        return new DeviceCreated(toViews(List.of(saved)).getFirst(), secret);
    }

    public PageView<DeviceView> list(String productKey, String keyword, int page, int size) {
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>().orderByDesc(Device::getId);
        if (productKey != null && !productKey.isBlank()) {
            query.eq(Device::getProductId, productService.require(productKey).getId());
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(Device::getName, keyword).or().like(Device::getDeviceKey, keyword));
        }
        Page<Device> result = devices.selectPage(new Page<>(page, size), query);
        return new PageView<>(toViews(result.getRecords()), result.getTotal(), page, size);
    }

    public DeviceView get(String deviceKey) {
        return toViews(List.of(require(deviceKey))).getFirst();
    }

    public DeviceView update(String deviceKey, UpdateDeviceRequest request) {
        Device d = require(deviceKey);
        boolean statusChanged = !d.getStatus().equals(request.status());
        // 只更新 name/status，避免用旧快照覆盖并发 reset-secret 写入的密钥
        devices.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getId, d.getId())
                .set(Device::getName, request.name())
                .set(Device::getStatus, request.status())
                .set(Device::getUpdatedAt, OffsetDateTime.now(clock)));
        if (statusChanged) {
            identityCache.evict(deviceKey);
        }
        return toViews(List.of(require(deviceKey))).getFirst();
    }

    public SecretView resetSecret(String deviceKey) {
        Device d = require(deviceKey);
        String secret = keys.deviceSecret();
        String salt = hasher.newSalt();
        devices.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getId, d.getId())
                .set(Device::getSecretHash, hasher.hash(secret, salt))
                .set(Device::getSecretSalt, salt)
                .set(Device::getUpdatedAt, OffsetDateTime.now(clock)));
        identityCache.evict(deviceKey);
        return new SecretView(deviceKey, secret);
    }

    public void delete(String deviceKey) {
        Device d = require(deviceKey);
        devices.deleteById(d.getId());
        bestEffort(deviceKey, "auth", () -> identityCache.evict(deviceKey));
        bestEffort(deviceKey, "latest", () -> latest.delete(deviceKey));
        bestEffort(deviceKey, "presence", () -> presence.delete(deviceKey));
    }

    private void bestEffort(String deviceKey, String keyName, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (DataAccessException e) {
            log.warn("Redis cleanup failed after device delete: deviceKey={}, key={}", deviceKey, keyName, e);
        }
    }

    public Device require(String deviceKey) {
        Device d = devices.selectOne(new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (d == null) {
            throw ApiException.notFound("设备不存在: " + deviceKey);
        }
        return d;
    }

    private List<DeviceView> toViews(List<Device> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<String> deviceKeys = list.stream().map(Device::getDeviceKey).toList();
        Map<Long, String> productKeys = products.selectBatchIds(list.stream().map(Device::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getProductKey));
        Map<String, Presence> presenceByDevice = presence.getAll(deviceKeys);
        Map<String, Long> lastReports = latest.lastReportTimes(deviceKeys);

        return list.stream().map(d -> {
            Presence p = presenceByDevice.get(d.getDeviceKey());
            return new DeviceView(d.getDeviceKey(), d.getName(), productKeys.get(d.getProductId()), d.getStatus(),
                    p != null && p.online(), lastReports.get(d.getDeviceKey()), d.getCreatedAt());
        }).toList();
    }
}
