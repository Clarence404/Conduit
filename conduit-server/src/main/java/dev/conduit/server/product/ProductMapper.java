package dev.conduit.server.product;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 跨租户按 productKey 查产品（流水线无租户上下文），不存在返回 null。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select * from product where product_key = #{productKey}")
    Product findByKeyAnyTenant(@Param("productKey") String productKey);
}
