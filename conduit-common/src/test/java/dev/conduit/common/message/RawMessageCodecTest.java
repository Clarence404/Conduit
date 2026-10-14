package dev.conduit.common.message;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawMessageCodecTest {

    @Test
    void roundTripsPropertyMessage() {
        RawMessage original = new RawMessage("pk1", "dk1", MessageType.PROPERTY,
                "{\"params\":{\"Ua\":220.1}}".getBytes(StandardCharsets.UTF_8),
                1_760_400_000_000L, Map.of("topic", "conduit/pk1/dk1/up/property"));

        RawMessage decoded = RawMessageCodec.fromFields(RawMessageCodec.toFields(original));

        assertThat(decoded).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void roundTripsPresenceMessageWithoutProductKey() {
        RawMessage original = new RawMessage(null, "dk1", MessageType.PRESENCE_CONNECTED,
                new byte[0], 1L, Map.of());

        RawMessage decoded = RawMessageCodec.fromFields(RawMessageCodec.toFields(original));

        assertThat(decoded.productKey()).isNull();
        assertThat(decoded).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void preservesBinaryPayloadExactly() {
        byte[] binary = {0, (byte) 0xFF, 0x10, (byte) 0x80};
        RawMessage original = new RawMessage("pk", "dk", MessageType.PROPERTY, binary, 1L, null);

        RawMessage decoded = RawMessageCodec.fromFields(RawMessageCodec.toFields(original));

        assertThat(decoded.payload()).containsExactly(binary);
    }

    @Test
    void rejectsFieldsWithoutDeviceKey() {
        Map<String, String> fields = new HashMap<>(RawMessageCodec.toFields(
                new RawMessage("pk", "dk", MessageType.PROPERTY, new byte[0], 1L, null)));
        fields.remove("dk");

        assertThatThrownBy(() -> RawMessageCodec.fromFields(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownType() {
        Map<String, String> fields = new HashMap<>(RawMessageCodec.toFields(
                new RawMessage("pk", "dk", MessageType.PROPERTY, new byte[0], 1L, null)));
        fields.put("type", "NOPE");

        assertThatThrownBy(() -> RawMessageCodec.fromFields(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> validFields() {
        return new HashMap<>(RawMessageCodec.toFields(
                new RawMessage("pk", "dk", MessageType.PROPERTY, new byte[0], 1L, null)));
    }

    @Test
    void rejectsMissingPayload() {
        Map<String, String> fields = validFields();
        fields.remove("payload");

        assertThatThrownBy(() -> RawMessageCodec.fromFields(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericReceivedAt() {
        Map<String, String> fields = validFields();
        fields.put("receivedAt", "yesterday");

        assertThatThrownBy(() -> RawMessageCodec.fromFields(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidBase64Payload() {
        Map<String, String> fields = validFields();
        fields.put("payload", "!!not-base64!!");

        assertThatThrownBy(() -> RawMessageCodec.fromFields(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toFieldsOmitsProductKeyWhenNull() {
        Map<String, String> fields = RawMessageCodec.toFields(
                new RawMessage(null, "dk", MessageType.PRESENCE_CONNECTED, new byte[0], 1L, null));

        assertThat(fields).doesNotContainKey("pk");
    }
}
