package dev.conduit.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public record SimulatorConfig(URI apiUrl, String adminUsername, String adminPassword,
                              String mqttHost, int mqttPort, String productName,
                              int deviceCount, Duration interval, int messagesPerDevice,
                              PayloadFormat payloadFormat) {

    /** 本地联调用的配置文件（classpath 根目录，不入库），键名与环境变量相同。 */
    static final String LOCAL_PROPERTIES = "simulator-local.properties";

    /** 环境变量优先，其次是 classpath 上可选的 simulator-local.properties。 */
    public static SimulatorConfig load() {
        return fromEnv(merge(readLocalProperties(), System.getenv()));
    }

    static Map<String, String> merge(Map<String, String> local, Map<String, String> env) {
        Map<String, String> merged = new HashMap<>(local);
        merged.putAll(env);
        return merged;
    }

    public static SimulatorConfig fromEnv(Map<String, String> env) {
        return new SimulatorConfig(
                URI.create(env.getOrDefault("CONDUIT_API_URL", "http://localhost:8080")),
                env.getOrDefault("CONDUIT_ADMIN_USERNAME", "admin"),
                require(env, "CONDUIT_ADMIN_PASSWORD"),
                env.getOrDefault("MQTT_HOST", "localhost"),
                Integer.parseInt(env.getOrDefault("MQTT_PORT", "1883")),
                env.getOrDefault("SIM_PRODUCT_NAME", "三相电表（演示）"),
                Integer.parseInt(env.getOrDefault("SIM_DEVICE_COUNT", "100")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("SIM_INTERVAL_MS", "10000"))),
                Integer.parseInt(env.getOrDefault("SIM_MESSAGES_PER_DEVICE", "0")),
                PayloadFormat.parse(env.getOrDefault("SIM_PAYLOAD_FORMAT", "standard")));
    }

    private static Map<String, String> readLocalProperties() {
        try (InputStream in = SimulatorConfig.class.getClassLoader().getResourceAsStream(LOCAL_PROPERTIES)) {
            if (in == null) {
                return Map.of();
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> values = new HashMap<>();
            props.stringPropertyNames().forEach(k -> values.put(k, props.getProperty(k).trim()));
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + LOCAL_PROPERTIES, e);
        }
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is required");
        }
        return value;
    }
}
