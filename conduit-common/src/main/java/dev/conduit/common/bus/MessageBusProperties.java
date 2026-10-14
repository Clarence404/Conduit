package dev.conduit.common.bus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "conduit.bus")
public record MessageBusProperties(
        @DefaultValue("conduit:stream:up") String streamKey,
        @DefaultValue("conduit:stream:dead") String deadKey,
        @DefaultValue("1000000") long maxLen,
        @DefaultValue("100000") long deadMaxLen) {
}
