package dev.conduit.server.device;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 设备的 MyBatis-Plus Mapper，常规查询受租户拦截器过滤；findIdentity 是显式绕过租户过滤的内部专用查询。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /**
     * 按产品与状态分组的设备数（一行）。
     *
     * @param productId 产品 id
     * @param status    ENABLED 或 DISABLED
     * @param count     设备数
     */
    record ProductStatusCount(long productId, String status, int count) {
    }

    /**
     * 跨租户按 deviceKey 查设备身份（联查产品取 productKey）。供 MQTT 鉴权与流水线使用，这两处没有租户上下文，禁止用于面向用户的接口。
     * 设备不存在返回 null。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select d.device_key, d.tenant_id, d.status, d.secret_hash, d.secret_salt, p.product_key
            from device d join product p on p.id = d.product_id
            where d.device_key = #{deviceKey}
            """)
    DeviceIdentity findIdentity(@Param("deviceKey") String deviceKey);

    /**
     * 当前租户的设备按产品、状态分组计数（一次 GROUP BY 查询）。
     *
     * @param productIds 限定的产品 id；null 表示当前租户全部产品，空集合直接返回空列表（不查库）
     */
    default List<ProductStatusCount> countByProductAndStatus(Collection<Long> productIds) {
        if (productIds != null && productIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<Device> query = new QueryWrapper<Device>().select("product_id as pid", "status", "count(*) as cnt");
        if (productIds != null) {
            query.in("product_id", productIds);
        }
        query.groupBy("product_id", "status");
        return selectMaps(query).stream()
                .map(r -> new ProductStatusCount(((Number) r.get("pid")).longValue(), (String) r.get("status"),
                        ((Number) r.get("cnt")).intValue()))
                .toList();
    }

    /**
     * 当前租户设备的精简列表，只取 id、product_id、device_key、name、status，供统计在线数与最近上报使用。
     *
     * @param productIds 限定的产品 id；null 表示当前租户全部产品，空集合直接返回空列表（不查库）
     */
    default List<Device> selectBrief(Collection<Long> productIds) {
        if (productIds != null && productIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>()
                .select(Device::getId, Device::getProductId, Device::getDeviceKey, Device::getName, Device::getStatus);
        if (productIds != null) {
            query.in(Device::getProductId, productIds);
        }
        return selectList(query);
    }
}
