package dev.conduit.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 读取 deploy/.env，让测试与 Compose 使用同一套凭证和端口。 */
final class DotEnv {

    private DotEnv() {
    }

    /** 返回必需键的值，缺失或为空时给出指明键名的错误。 */
    static String require(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required key " + key + " in deploy/.env");
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    static Map<String, String> load(Path file) {
        try {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                values.put(trimmed.substring(0, eq).trim(), unquote(trimmed.substring(eq + 1).trim()));
            }
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), e);
        }
    }
}
