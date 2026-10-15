package dev.conduit.server.thingmodel;

import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductPropertyMapperIT extends IntegrationTestBase {

    @Autowired
    ProductPropertyMapper properties;

    private long productId(String productKey) {
        return jdbc.queryForObject("select id from product where product_key = ?", Long.class, productKey);
    }

    private static ProductProperty property(long productId, String identifier, DataType type) {
        ProductProperty p = new ProductProperty();
        p.setProductId(productId);
        p.setIdentifier(identifier);
        p.setName(identifier + " name");
        p.setDataType(type);
        return p;
    }

    private void insertRaw(long tenantId, long productId, String identifier, String dataType,
                           Double min, Double max, Integer decimals, String enumItemsJson) {
        jdbc.update("insert into product_property (tenant_id, product_id, identifier, name, data_type, min_value, "
                        + "max_value, decimals, enum_items) values (?, ?, ?, 'n', ?, cast(? as double precision), "
                        + "cast(? as double precision), cast(? as smallint), cast(? as jsonb))",
                tenantId, productId, identifier, dataType, min, max, decimals, enumItemsJson);
    }

    @Test
    void existingProductsDefaultToStandardJsonCodec() {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        assertThat(jdbc.queryForObject("select codec from product where product_key = ?", String.class,
                d.productKey())).isEqualTo("standard-json");
    }

    @Test
    void roundTripsEnumItemsAndFillsTenant() {
        long tenant = defaultTenantId();
        long pid = productId(createDevice(tenant, "ENABLED").productKey());
        ProductProperty p = property(pid, "st", DataType.ENUM);
        p.setDescription("运行状态");
        p.setEnumItems(List.of(new EnumItem(0, "停止"), new EnumItem(1, "运行")));

        TenantContext.runAs(tenant, () -> properties.insert(p));
        ProductProperty loaded = TenantContext.callAs(tenant, () -> properties.selectById(p.getId()));

        assertThat(loaded.getTenantId()).isEqualTo(tenant);
        assertThat(loaded.getDataType()).isEqualTo(DataType.ENUM);
        assertThat(loaded.getEnumItems()).containsExactly(new EnumItem(0, "停止"), new EnumItem(1, "运行"));
        assertThat(loaded.getDescription()).isEqualTo("运行状态");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void updateByIdClearsNullableColumns() {
        long tenant = defaultTenantId();
        long pid = productId(createDevice(tenant, "ENABLED").productKey());
        ProductProperty p = property(pid, "Ua", DataType.DOUBLE);
        p.setUnit("V");
        p.setMinValue(0.0);
        p.setMaxValue(500.0);
        p.setDecimals(1);
        TenantContext.runAs(tenant, () -> properties.insert(p));

        p.setUnit(null);
        p.setMinValue(null);
        p.setMaxValue(null);
        p.setDecimals(null);
        TenantContext.runAs(tenant, () -> properties.updateById(p));
        ProductProperty loaded = TenantContext.callAs(tenant, () -> properties.selectById(p.getId()));

        assertThat(loaded.getUnit()).isNull();
        assertThat(loaded.getMinValue()).isNull();
        assertThat(loaded.getMaxValue()).isNull();
        assertThat(loaded.getDecimals()).isNull();
    }

    @Test
    void identifierIsUniquePerProduct() {
        long tenant = defaultTenantId();
        long pid = productId(createDevice(tenant, "ENABLED").productKey());
        long other = productId(createDevice(tenant, "ENABLED").productKey());
        TenantContext.runAs(tenant, () -> properties.insert(property(pid, "Ua", DataType.DOUBLE)));

        assertThatThrownBy(() -> TenantContext.runAs(tenant,
                () -> properties.insert(property(pid, "Ua", DataType.DOUBLE))))
                .isInstanceOf(DuplicateKeyException.class);
        TenantContext.runAs(tenant, () -> properties.insert(property(other, "Ua", DataType.DOUBLE)));
    }

    @Test
    void databaseRejectsInvalidRows() {
        long tenant = defaultTenantId();
        long pid = productId(createDevice(tenant, "ENABLED").productKey());

        assertThatThrownBy(() -> insertRaw(tenant, pid, "a1", "FLOAT", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "a2", "ENUM", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "a3", "DOUBLE", null, null, null, "[{\"value\":1,\"label\":\"x\"}]"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "a4", "DOUBLE", 5.0, 1.0, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "a5", "DOUBLE", null, null, 7, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "a6", "BOOL", 0.0, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "1a", "DOUBLE", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(tenant, pid, "a7", "ENUM", null, null, null, "{\"value\":1}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingProductCascadesToProperties() {
        long tenant = defaultTenantId();
        long pid = jdbc.queryForObject("insert into product (tenant_id, product_key, name) values (?, ?, 'p') returning id",
                Long.class, tenant, unique("pk"));
        TenantContext.runAs(tenant, () -> properties.insert(property(pid, "Ua", DataType.DOUBLE)));

        jdbc.update("delete from product where id = ?", pid);

        assertThat(jdbc.queryForObject("select count(*) from product_property where product_id = ?", Long.class, pid))
                .isZero();
    }

    @Test
    void countsPropertiesPerProduct() {
        long tenant = defaultTenantId();
        long a = productId(createDevice(tenant, "ENABLED").productKey());
        long b = productId(createDevice(tenant, "ENABLED").productKey());
        TenantContext.runAs(tenant, () -> {
            properties.insert(property(a, "Ua", DataType.DOUBLE));
            properties.insert(property(a, "Ub", DataType.DOUBLE));
        });

        assertThat(TenantContext.callAs(tenant, () -> properties.countByProduct(a))).isEqualTo(2);
        Map<Long, Integer> counts = TenantContext.callAs(tenant, () -> properties.countByProducts(List.of(a, b)));
        assertThat(counts).containsEntry(a, 2).doesNotContainKey(b);
        assertThat(TenantContext.callAs(tenant, () -> properties.countByProducts(List.of()))).isEmpty();
    }
}
