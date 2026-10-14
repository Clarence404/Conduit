package dev.conduit.server.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conduit.bootstrap")
public record BootstrapProperties(String adminUsername, String adminPassword) {
}
