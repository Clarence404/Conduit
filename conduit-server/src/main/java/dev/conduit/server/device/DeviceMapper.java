package dev.conduit.server.device;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备的 MyBatis-Plus Mapper，常规查询受租户拦截器过滤。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

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
}
