package dev.conduit.server.pipeline;

import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.PropertySpec;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldValidatorTest {

    private static PropertySpec spec(String id, DataType type, Double min, Double max, Map<Long, String> labels) {
        return new PropertySpec(id, id, type, null, min, max, null, labels);
    }

    private static PropertySpec spec(String id, DataType type) {
        return spec(id, type, null, null, Map.of());
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            values.put((String) kv[i], kv[i + 1]);
        }
        return values;
    }

    /** 单测点：返回接受后的值，或剔除原因。 */
    private static Object check(PropertySpec spec, Object value) {
        FieldValidation r = FieldValidator.validate(Map.of(spec.identifier(), spec), values(spec.identifier(), value));
        return r.rejected().isEmpty() ? r.accepted().get(spec.identifier()) : r.rejected().getFirst().reason();
    }

    @Test
    void withoutModelBehavesLikeV01() {
        FieldValidation r = FieldValidator.validate(Map.of(),
                values("Ua", 220L, "big", BigInteger.TEN.pow(20), "Ia", 5.2, "mode", "auto", "on", true));

        assertThat(r.rejected()).isEmpty();
        assertThat(r.accepted()).containsEntry("Ua", 220.0).containsEntry("big", 1e20).containsEntry("Ia", 5.2)
                .containsEntry("mode", "auto").containsEntry("on", true);
        assertThat(r.accepted().get("Ua")).isInstanceOf(Double.class);
    }

    @Test
    void withoutModelNonFiniteNumbersRejectTheWholeMessage() {
        for (Object bad : new Object[]{Double.POSITIVE_INFINITY, Double.NaN, BigInteger.TEN.pow(400)}) {
            assertThatThrownBy(() -> FieldValidator.validate(Map.of(), values("Ua", bad)))
                    .isInstanceOf(DecodeException.class)
                    .satisfies(e -> assertThat(((DecodeException) e).reason()).isEqualTo(DeadReason.INVALID_PARAMS));
        }
    }

    @Test
    void undefinedFieldsAreRejectedAndTheRestAccepted() {
        FieldValidation r = FieldValidator.validate(Map.of("Ua", spec("Ua", DataType.DOUBLE)),
                values("Ua", 220.1, "Zz", 1L));

        assertThat(r.accepted()).containsExactly(Map.entry("Ua", 220.1));
        assertThat(r.rejected()).containsExactly(new RejectedField("Zz", 1L, RejectReason.UNDEFINED));
    }

    @Test
    void doubleAcceptsFiniteNumbersAsDouble() {
        PropertySpec s = spec("v", DataType.DOUBLE);
        assertThat(check(s, 220L)).isEqualTo(220.0);
        assertThat(check(s, 5.5)).isEqualTo(5.5);
        assertThat(check(s, BigInteger.TEN.pow(20))).isEqualTo(1e20);
        assertThat(check(s, "220")).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, true)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, Double.POSITIVE_INFINITY)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, BigInteger.TEN.pow(400))).isEqualTo(RejectReason.TYPE_MISMATCH);
    }

    @Test
    void numericRangeIsInclusive() {
        PropertySpec d = spec("v", DataType.DOUBLE, 0.0, 300.0, Map.of());
        assertThat(check(d, 300.0)).isEqualTo(300.0);
        assertThat(check(d, 0L)).isEqualTo(0.0);
        assertThat(check(d, 300.1)).isEqualTo(RejectReason.OUT_OF_RANGE);
        assertThat(check(d, -1L)).isEqualTo(RejectReason.OUT_OF_RANGE);

        PropertySpec i = spec("v", DataType.INT, null, 10.0, Map.of());
        assertThat(check(i, 10L)).isEqualTo(10L);
        assertThat(check(i, 11L)).isEqualTo(RejectReason.OUT_OF_RANGE);
    }

    @Test
    void intAcceptsIntegersInLongRange() {
        PropertySpec s = spec("v", DataType.INT);
        assertThat(check(s, 42L)).isEqualTo(42L);
        assertThat(check(s, 42.0)).isEqualTo(42L);
        assertThat(check(s, -0.0)).isEqualTo(0L);
        assertThat(check(s, BigInteger.valueOf(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
        assertThat(check(s, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, 1.5)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, 9.3e18)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, Double.NaN)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, "1")).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, false)).isEqualTo(RejectReason.TYPE_MISMATCH);
    }

    @Test
    void boolAcceptsOnlyBooleans() {
        PropertySpec s = spec("v", DataType.BOOL);
        assertThat(check(s, true)).isEqualTo(true);
        assertThat(check(s, 1L)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, "true")).isEqualTo(RejectReason.TYPE_MISMATCH);
    }

    @Test
    void textAcceptsStringsUpTo1024() {
        PropertySpec s = spec("v", DataType.TEXT);
        assertThat(check(s, "auto")).isEqualTo("auto");
        assertThat(check(s, "x".repeat(1024))).isEqualTo("x".repeat(1024));
        assertThat(check(s, "x".repeat(1025))).isEqualTo(RejectReason.TOO_LONG);
        assertThat(check(s, 5L)).isEqualTo(RejectReason.TYPE_MISMATCH);
    }

    @Test
    void enumAcceptsDefinedIntegerValues() {
        PropertySpec s = spec("v", DataType.ENUM, null, null, Map.of(0L, "停止", 1L, "运行"));
        assertThat(check(s, 1L)).isEqualTo(1L);
        assertThat(check(s, 1.0)).isEqualTo(1L);
        assertThat(check(s, 2L)).isEqualTo(RejectReason.ENUM_VALUE);
        assertThat(check(s, 1.5)).isEqualTo(RejectReason.TYPE_MISMATCH);
        assertThat(check(s, "1")).isEqualTo(RejectReason.TYPE_MISMATCH);
    }

    @Test
    void keepsOriginalOrderOfAcceptedAndRejected() {
        Map<String, PropertySpec> model = Map.of("a", spec("a", DataType.DOUBLE), "b", spec("b", DataType.BOOL),
                "c", spec("c", DataType.DOUBLE));
        FieldValidation r = FieldValidator.validate(model, values("c", 1.0, "x", 1L, "a", 2.0, "b", 3L));

        assertThat(r.accepted().keySet()).containsExactly("c", "a");
        assertThat(r.rejected()).extracting(RejectedField::field).containsExactly("x", "b");
    }
}
