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

/**
 * 设备管理：创建、查询、修改、重置密钥、删除。设备属于当前租户（租户过滤由拦截器完成）；
 * 在线状态来自 Redis Presence，最近上报时间来自最新值存储。涉及接入鉴权的变更要同步清除身份缓存。
 */
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

    /**
     * 创建设备：校验产品存在（不存在抛 404），生成 deviceKey 与明文密钥，库中只存加盐哈希。明文密钥只在返回值中出现这一次。
     */
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

    /**
     * 分页列出设备，按 id 倒序。productKey 非空时限定产品（不存在抛 404）；keyword 非空时模糊匹配名称或 deviceKey。page 从 1 开始。
     */
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

    /**
     * 查询单个设备（含在线状态与最近上报时间），不存在抛 404。
     */
    public DeviceView get(String deviceKey) {
        return toViews(List.of(require(deviceKey))).getFirst();
    }

    /**
     * 修改名称与启用状态。只更新这两列（以及更新时间），不会覆盖并发重置的密钥；状态确有变化时清除身份缓存，使禁用/启用立即对鉴权生效。
     */
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

    /**
     * 重置设备密钥：生成新密钥与盐，更新后清除身份缓存，旧密钥随之失效。返回新的明文密钥（仅此一次）。
     */
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

    /**
     * 删除设备。先删库，再尽力清理 Redis（身份缓存、最新值、在线状态）；Redis 清理失败只记录告警，不影响删除结果。InfluxDB 历史数据不删，随保留期过期。
     */
    public void delete(String deviceKey) {
        Device d = require(deviceKey);
        devices.deleteById(d.getId());
        bestEffort(deviceKey, "auth", () -> identityCache.evict(deviceKey));
        bestEffort(deviceKey, "latest", () -> latest.delete(deviceKey));
        bestEffort(deviceKey, "presence", () -> presence.delete(deviceKey));
    }

    /**
     * 执行清理动作，Redis 异常只记录日志，不向上抛出。
     */
    private void bestEffort(String deviceKey, String keyName, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (DataAccessException e) {
            log.warn("Redis cleanup failed after device delete: deviceKey={}, key={}", deviceKey, keyName, e);
        }
    }

    /**
     * 按 deviceKey 取当前租户的设备实体，不存在（含属于其他租户）抛 404。供其他服务复用。
     */
    public Device require(String deviceKey) {
        Device d = devices.selectOne(new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (d == null) {
            throw ApiException.notFound("设备不存在: " + deviceKey);
        }
        return d;
    }

    /**
     * 批量转为响应视图：产品标识、在线状态、最近上报时间都各用一次批量查询取得，避免逐个设备查询。
     */
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
