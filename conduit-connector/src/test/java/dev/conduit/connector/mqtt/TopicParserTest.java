package dev.conduit.connector.mqtt;

import dev.conduit.common.message.MessageType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicParserTest {

    @Test
    void parsesPropertyTopic() {
        assertThat(TopicParser.parse("conduit/pk1/dk1/up/property"))
                .contains(new ParsedTopic("pk1", "dk1", MessageType.PROPERTY));
    }

    @Test
    void parsesEventTopic() {
        assertThat(TopicParser.parse("conduit/pk1/dk1/up/event"))
                .contains(new ParsedTopic("pk1", "dk1", MessageType.EVENT));
    }

    @Test
    void parsesConnectedSystemTopic() {
        assertThat(TopicParser.parse("$SYS/brokers/emqx@node1/clients/dk1/connected"))
                .contains(new ParsedTopic(null, "dk1", MessageType.PRESENCE_CONNECTED));
    }

    @Test
    void parsesDisconnectedSystemTopic() {
        assertThat(TopicParser.parse("$SYS/brokers/emqx@node1/clients/dk1/disconnected"))
                .contains(new ParsedTopic(null, "dk1", MessageType.PRESENCE_DISCONNECTED));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "conduit/pk1/dk1/up/unknown",
            "conduit/pk1/dk1/down/command",
            "conduit/pk1/dk1/up",
            "conduit//dk1/up/property",
            "conduit/pk1//up/property",
            "other/pk1/dk1/up/property",
            "conduit/pk1/dk1/up/property/extra",
            "$SYS/brokers/n/clients/dk1/subscribed",
            "$SYS/brokers/n/stats/connections",
            ""
    })
    void rejectsUnroutableTopics(String topic) {
        assertThat(TopicParser.parse(topic)).isEmpty();
    }
}
