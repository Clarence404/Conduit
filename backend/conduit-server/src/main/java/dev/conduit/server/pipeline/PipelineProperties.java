package dev.conduit.server.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "conduit.pipeline")
public record PipelineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("conduit-server") String group,
        @DefaultValue("server-1") String consumer,
        @DefaultValue("100") int batchSize,
        @DefaultValue("1s") Duration block,
        @DefaultValue("60s") Duration claimMinIdle,
        @DefaultValue("10s") Duration claimInterval,
        @DefaultValue("5") int maxDeliveries) {
}
