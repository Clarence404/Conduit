package dev.conduit.server.telemetry;

import java.util.Map;

/**
 * InfluxDB 行协议构造，只覆盖本项目用到的子集：一个 measurement、若干 tag、若干类型化字段、毫秒时间戳。
 * 字段类型：Long → integer（123i），有限 Double → float（220.0 / 1.0E20），Boolean，String；其余一律拒绝。
 * 行协议无法无歧义表示的名称（含反斜杠或换行、为空）直接拒绝，由调用方按转换失败处理。
 */
public final class LineProtocol {

    private LineProtocol() {
    }

    /**
     * 构造一行 InfluxDB 行协议：measurement,tag=.. field=.. 毫秒时间戳。
     * fields 为空或 null、名称为空/含非法字符、字段值类型不支持时抛 IllegalArgumentException。tag 按传入 Map 的迭代顺序输出。
     */
    public static String line(String measurement, Map<String, String> tags, Map<String, Object> fields, long tsMillis) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("At least one field is required");
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(escape(measurement, false));
        tags.forEach((key, value) -> sb.append(',').append(escape(key, true)).append('=').append(escape(value, true)));
        char separator = ' ';
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            sb.append(separator).append(escape(field.getKey(), true)).append('=')
                    .append(fieldValue(field.getKey(), field.getValue()));
            separator = ',';
        }
        return sb.append(' ').append(tsMillis).toString();
    }

    /**
     * 按类型格式化字段值：Long 加 i 后缀，Double 必须有限，Boolean 原样，String 加引号并转义反斜杠与双引号；
     * null、非有限数值和其他类型抛 IllegalArgumentException。name 仅用于错误消息。
     */
    static String fieldValue(String name, Object value) {
        return switch (value) {
            case Long l -> l + "i";
            case Double d -> {
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("Non-finite value for field " + name);
                }
                yield Double.toString(d);
            }
            case Boolean b -> b.toString();
            case String s -> '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            case null -> throw new IllegalArgumentException("Null value for field " + name);
            default -> throw new IllegalArgumentException(
                    "Unsupported field type " + value.getClass().getSimpleName() + " for field " + name);
        };
    }

    /** measurement 转义逗号与空格；tag 键值与字段名另外转义等号。 */
    private static String escape(String s, boolean escapeEquals) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("Empty measurement, key or tag value");
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\n' || c == '\r') {
                throw new IllegalArgumentException("Backslash or line break is not supported in '" + s + "'");
            }
            if (c == ',' || c == ' ' || (escapeEquals && c == '=')) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
