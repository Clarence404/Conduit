package dev.conduit.simulator;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record SimulatorConfig(URI apiUrl, String adminUsername, String adminPassword,
                              String mqttHost, int mqttPort, String productName,
                              int deviceCount, Duration interval, int messagesPerDevice) {

    public static SimulatorConfig fromEnv(Map<String, String> env) {
        return new SimulatorConfig(
                URI.create(env.getOrDefault("CONDUIT_API_URL", "http://localhost:8080")),
                env.getOrDefault("CONDUIT_ADMIN_USERNAME", "admin"),
                require(env, "CONDUIT_ADMIN_PASSWORD"),
                env.getOrDefault("MQTT_HOST", "localhost"),
                Integer.parseInt(env.getOrDefault("MQTT_PORT", "1883")),
                env.getOrDefault("SIM_PRODUCT_NAME", "Demo Meter"),
                Integer.parseInt(env.getOrDefault("SIM_DEVICE_COUNT", "100")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("SIM_INTERVAL_MS", "10000"))),
                Integer.parseInt(env.getOrDefault("SIM_MESSAGES_PER_DEVICE", "0")));
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is required");
        }
        return value;
    }
}
