package dev.conduit.server.device;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select d.device_key, d.tenant_id, d.status, d.secret_hash, d.secret_salt, p.product_key
            from device d join product p on p.id = d.product_id
            where d.device_key = #{deviceKey}
            """)
    DeviceIdentity findIdentity(@Param("deviceKey") String deviceKey);
}
