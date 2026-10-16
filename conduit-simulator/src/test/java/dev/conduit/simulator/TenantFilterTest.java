package dev.conduit.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** TenantFilter 单元测试：* 与空值表示全部租户，逗号分隔的编码精确匹配并忽略空白。 */
class TenantFilterTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"*", "", "  ", " * ", ",", "east,*"})
    void starEmptyOrAListWithStarMeansAllTenants(String value) {
        TenantFilter filter = TenantFilter.parse(value);

        assertThat(filter.all()).isTrue();
        assertThat(filter.includes("anything")).isTrue();
    }

    @Test
    void aListMatchesExactCodesIgnoringWhitespace() {
        TenantFilter filter = TenantFilter.parse(" east-power , south-water,,");

        assertThat(filter.all()).isFalse();
        assertThat(filter.codes()).containsExactlyInAnyOrder("east-power", "south-water");
        assertThat(filter.includes("east-power")).isTrue();
        assertThat(filter.includes("south-water")).isTrue();
        assertThat(filter.includes("east")).isFalse();
        assertThat(filter.includes("default")).isFalse();
    }
}
