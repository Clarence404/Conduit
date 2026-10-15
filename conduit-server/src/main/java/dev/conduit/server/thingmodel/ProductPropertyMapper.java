package dev.conduit.server.thingmodel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品属性（物模型）的 MyBatis-Plus Mapper，受租户拦截器过滤。
 */
@Mapper
public interface ProductPropertyMapper extends BaseMapper<ProductProperty> {

    /**
     * 统计某产品的属性数。
     */
    default long countByProduct(long productId) {
        return selectCount(new LambdaQueryWrapper<ProductProperty>().eq(ProductProperty::getProductId, productId));
    }

    /** 每个产品的属性数；没有属性的产品不在结果中。 */
    default Map<Long, Integer> countByProducts(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = selectMaps(new QueryWrapper<ProductProperty>()
                .select("product_id as pid", "count(*) as cnt")
                .in("product_id", productIds)
                .groupBy("product_id"));
        Map<Long, Integer> counts = new HashMap<>();
        rows.forEach(r -> counts.put(((Number) r.get("pid")).longValue(), ((Number) r.get("cnt")).intValue()));
        return counts;
    }
}
