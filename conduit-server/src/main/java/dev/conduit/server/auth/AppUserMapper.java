package dev.conduit.server.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** {@link AppUser} 的 MyBatis-Plus Mapper（PostgreSQL 的 app_user 表）。 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
