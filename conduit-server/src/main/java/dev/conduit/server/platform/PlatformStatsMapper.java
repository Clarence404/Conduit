package dev.conduit.server.platform;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台级跨租户统计查询。每个方法都显式绕过租户行过滤（@InterceptorIgnore），只供平台管理员接口
 * （平台概况、租户列表、停用时清缓存）在内部使用，禁止用于租户内接口。
 */
@Mapper
public interface PlatformStatsMapper {

    /** 某租户的计数（一行）。 */
    record TenantCount(long tenantId, long n) {
    }

    /** 某租户的一台设备。 */
    record TenantDevice(long tenantId, String deviceKey) {
    }

    /** 各租户的产品数（没有产品的租户不出现）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select tenant_id, count(*) as n from product group by tenant_id")
    List<TenantCount> productCounts();

    /** 全平台设备的租户与 deviceKey。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select tenant_id, device_key from device")
    List<TenantDevice> devices();

    /** 指定租户全部设备的 deviceKey（停用/启用租户时清除身份缓存用）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select device_key from device where tenant_id = #{tenantId}")
    List<String> deviceKeysOf(@Param("tenantId") long tenantId);
}
