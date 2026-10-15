package dev.conduit.server.tenant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** {@link Tenant} 的 MyBatis-Plus Mapper（PostgreSQL 的 tenant 表）。 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
