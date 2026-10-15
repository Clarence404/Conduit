package dev.conduit.server.telemetry;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineProtocolTest {

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tenant_id", "1");
        tags.put("product_key", "pk");
        tags.put("device_key", "dk");
        return tags;
    }

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            fields.put((String) kv[i], kv[i + 1]);
        }
        return fields;
    }

    @Test
    void writesTypedFieldsInOrder() {
        String line = LineProtocol.line("property", tags(),
                fields("a", 5L, "b", 1.5, "c", 220.0, "d", true, "e", "x"), 1000L);

        assertThat(line).isEqualTo(
                "property,tenant_id=1,product_key=pk,device_key=dk a=5i,b=1.5,c=220.0,d=true,e=\"x\" 1000");
    }

    @Test
    void integralDoublesStayFloatsAndLargeDoublesUseExponent() {
        assertThat(LineProtocol.fieldValue("f", 220.0)).isEqualTo("220.0");
        assertThat(LineProtocol.fieldValue("f", -0.0)).isEqualTo("-0.0");
        assertThat(LineProtocol.fieldValue("f", 1e20)).isEqualTo("1.0E20");
        assertThat(LineProtocol.fieldValue("f", Long.MIN_VALUE)).isEqualTo("-9223372036854775808i");
    }

    @Test
    void escapesKeysTagValuesAndMeasurement() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("device key", "a b,c=d");

        String line = LineProtocol.line("my m,x", tags, fields("my field,x=y", 1.0), 7L);

        assertThat(line).isEqualTo("my\\ m\\,x,device\\ key=a\\ b\\,c\\=d my\\ field\\,x\\=y=1.0 7");
    }

    @Test
    void escapesQuotesAndBackslashesInStringValuesAndKeepsNewlines() {
        assertThat(LineProtocol.fieldValue("s", "x\\y\"z")).isEqualTo("\"x\\\\y\\\"z\"");
        assertThat(LineProtocol.fieldValue("s", "line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(LineProtocol.fieldValue("s", "电压")).isEqualTo("\"电压\"");
    }

    @Test
    void rejectsUnsupportedOrNonFiniteValues() {
        assertThatThrownBy(() -> LineProtocol.fieldValue("f", Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.fieldValue("f", Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.fieldValue("f", 5)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Integer");
        assertThatThrownBy(() -> LineProtocol.fieldValue("f", BigInteger.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.fieldValue("f", List.of(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.fieldValue("f", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysThatLineProtocolCannotRepresent() {
        assertThatThrownBy(() -> LineProtocol.line("property", tags(), fields("a\\b", 1.0), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.line("property", tags(), fields("a\nb", 1.0), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.line("property", tags(), fields("", 1.0), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> emptyTag = new LinkedHashMap<>();
        emptyTag.put("device_key", "");
        assertThatThrownBy(() -> LineProtocol.line("property", emptyTag, fields("a", 1.0), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAtLeastOneField() {
        assertThatThrownBy(() -> LineProtocol.line("property", tags(), Map.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineProtocol.line("property", tags(), null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("a", null);
        assertThatThrownBy(() -> LineProtocol.line("property", tags(), nullValue, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
