package dev.conduit.server.thingmodel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.product.ProductService;
import dev.conduit.server.thingmodel.ThingModelDtos.PropertyRequest;
import dev.conduit.server.thingmodel.ThingModelDtos.PropertyView;
import dev.conduit.server.thingmodel.ThingModelDtos.ThingModelDocument;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.ItemErrorsException;
import dev.conduit.server.web.ItemErrorsException.ItemError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 属性增删改查与物模型导入导出。所有修改在提交后清除该产品的物模型缓存。 */
@Service
public class ThingModelService {

    private final ProductService products;
    private final ProductMapper productMapper;
    private final ProductPropertyMapper properties;
    private final ThingModelCache cache;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ThingModelService(ProductService products, ProductMapper productMapper, ProductPropertyMapper properties,
                             ThingModelCache cache, TransactionTemplate tx, Clock clock) {
        this.products = products;
        this.productMapper = productMapper;
        this.properties = properties;
        this.cache = cache;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 列出产品属性，按 id（创建顺序）升序。产品不存在抛 404。
     */
    public List<PropertyView> list(String productKey) {
        return rows(products.require(productKey).getId()).stream().map(PropertyView::of).toList();
    }

    /**
     * 新增属性。校验失败抛 400；在事务内先锁产品行，再检查标识重复和 200 个上限（冲突抛 409），保证并发新增/导入也不会超限。提交后清除物模型缓存。
     */
    public PropertyView create(String productKey, PropertyRequest request) {
        Product product = products.require(productKey);
        requireValid(request);
        ProductProperty created = tx.execute(status -> {
            productMapper.lockById(product.getId());
            if (findRow(product.getId(), request.identifier()) != null) {
                throw ApiException.conflict("属性标识已存在: " + request.identifier());
            }
            if (properties.countByProduct(product.getId()) >= PropertyRules.MAX_PROPERTIES) {
                throw ApiException.conflict("每个产品最多 " + PropertyRules.MAX_PROPERTIES + " 个属性");
            }
            ProductProperty row = newRow(product.getId(), request);
            properties.insert(row);
            return properties.selectById(row.getId());
        });
        cache.evict(productKey);
        return PropertyView.of(created);
    }

    /**
     * 修改属性。标识取路径参数；属性不存在抛 404，数据类型与现有不同抛 409，字段不合法抛 400。可清空的字段（单位、范围、说明等）传 null 即清空。成功后清除物模型缓存。
     */
    public PropertyView update(String productKey, String identifier, PropertyRequest request) {
        Product product = products.require(productKey);
        ProductProperty row = requireRow(product.getId(), identifier);
        PropertyRequest effective = request.withIdentifier(identifier);
        DataType requested = PropertyRules.parseType(effective.dataType());
        if (requested != null && requested != row.getDataType()) {
            throw ApiException.conflict("数据类型不可修改");
        }
        requireValid(effective);
        PropertyRules.apply(effective, row);
        row.setUpdatedAt(OffsetDateTime.now(clock));
        properties.updateById(row);
        cache.evict(productKey);
        return PropertyView.of(properties.selectById(row.getId()));
    }

    /**
     * 删除属性；不存在抛 404。成功后清除物模型缓存。
     */
    public void delete(String productKey, String identifier) {
        Product product = products.require(productKey);
        properties.deleteById(requireRow(product.getId(), identifier).getId());
        cache.evict(productKey);
    }

    /**
     * 导出产品的全部属性（仅 9 个可编辑字段，不含时间戳），可直接再导入。
     */
    public ThingModelDocument export(String productKey) {
        return new ThingModelDocument(rows(products.require(productKey).getId()).stream()
                .map(PropertyRequest::of).toList());
    }

    /**
     * 导入物模型，在单个事务内完成：锁产品行，先校验整份文件（任一错误抛 ItemErrorsException，未做任何修改），
     * 再逐项新增或更新，REPLACE 模式最后删除文件中没有的属性。提交后清除缓存，返回导入后的全部属性。文档缺 properties 数组抛 400。
     */
    public List<PropertyView> importModel(String productKey, ThingModelDocument document, ImportMode mode) {
        Product product = products.require(productKey);
        if (document == null || document.properties() == null) {
            throw ApiException.badRequest("文件缺少 properties 数组");
        }
        List<ProductProperty> result = tx.execute(status -> {
            productMapper.lockById(product.getId());
            Map<String, ProductProperty> existing = new LinkedHashMap<>();
            rows(product.getId()).forEach(r -> existing.put(r.getIdentifier(), r));
            Map<String, DataType> existingTypes = new LinkedHashMap<>();
            existing.forEach((id, r) -> existingTypes.put(id, r.getDataType()));
            List<ItemError> errors = ThingModelImport.validate(document.properties(), existingTypes, mode);
            if (!errors.isEmpty()) {
                throw new ItemErrorsException("物模型导入失败，共 " + errors.size() + " 处错误，未做任何修改", errors);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            Set<String> inFile = new HashSet<>();
            for (PropertyRequest item : document.properties()) {
                inFile.add(item.identifier());
                ProductProperty row = existing.get(item.identifier());
                if (row == null) {
                    properties.insert(newRow(product.getId(), item));
                } else {
                    PropertyRules.apply(item, row);
                    row.setUpdatedAt(now);
                    properties.updateById(row);
                }
            }
            if (mode == ImportMode.REPLACE) {
                existing.values().stream()
                        .filter(r -> !inFile.contains(r.getIdentifier()))
                        .forEach(r -> properties.deleteById(r.getId()));
            }
            return rows(product.getId());
        });
        cache.evict(productKey);
        return result.stream().map(PropertyView::of).toList();
    }

    /**
     * 由已通过校验的请求构造新属性实体。
     */
    private static ProductProperty newRow(long productId, PropertyRequest request) {
        ProductProperty row = new ProductProperty();
        row.setProductId(productId);
        row.setIdentifier(request.identifier());
        row.setDataType(DataType.valueOf(request.dataType()));
        PropertyRules.apply(request, row);
        return row;
    }

    /**
     * 校验不通过时把全部错误用「；」拼成一条 400。
     */
    private static void requireValid(PropertyRequest request) {
        List<String> errors = PropertyRules.check(request);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("；", errors));
        }
    }

    /**
     * 读取产品的全部属性，按 id 升序。
     */
    private List<ProductProperty> rows(long productId) {
        return properties.selectList(new LambdaQueryWrapper<ProductProperty>()
                .eq(ProductProperty::getProductId, productId).orderByAsc(ProductProperty::getId));
    }

    /**
     * 按标识查属性，不存在返回 null。
     */
    private ProductProperty findRow(long productId, String identifier) {
        return properties.selectOne(new LambdaQueryWrapper<ProductProperty>()
                .eq(ProductProperty::getProductId, productId).eq(ProductProperty::getIdentifier, identifier));
    }

    /**
     * 按标识查属性，不存在抛 404。
     */
    private ProductProperty requireRow(long productId, String identifier) {
        ProductProperty row = findRow(productId, identifier);
        if (row == null) {
            throw ApiException.notFound("属性不存在: " + identifier);
        }
        return row;
    }
}
