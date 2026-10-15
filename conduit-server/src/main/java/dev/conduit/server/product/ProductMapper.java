package dev.conduit.server.product;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 产品的 MyBatis-Plus Mapper，常规查询受租户拦截器过滤；下面两个方法是显式绕过租户过滤的内部专用查询。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 跨租户按 productKey 查产品（流水线无租户上下文），不存在返回 null。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select * from product where product_key = #{productKey}")
    Product findByKeyAnyTenant(@Param("productKey") String productKey);

    /** 在事务中锁定产品行，串行化同一产品的属性新增与导入（保证 200 个上限）。id 已经过租户校验。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select id from product where id = #{id} for update")
    Long lockById(@Param("id") long id);
}
