package dev.conduit.server.presence;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备在线状态的 Redis 存储（hash：online / since / clientIp / source / lastSeen）。写入通过 Lua 脚本保证原子与乱序保护，
 * 由流水线的 {@link PresenceHandler}（EMQX 上下线事件）与 {@link dev.conduit.server.pipeline.PipelineProcessor}（上报数据）写入，
 * REST 查询设备在线状态时读取。
 * <p>
 * 状态有两个来源，可信度不同：
 * <ul>
 *   <li><b>事件</b>（{@link #apply}）：EMQX 的 connected_at / disconnected_at，时间来自 EMQX 时钟，是权威来源。
 *       只接受 {@code eventTime >= since} 的事件（since 是唯一的乱序保护，旧事件后到不会覆盖新状态）。</li>
 *   <li><b>数据</b>（{@link #applyData}）：设备上报数据说明它在线，用来补偿「设备在 transport 订阅之前就已连接、
 *       没有收到连接事件」。时间是 transport 主机的接收时间，与 EMQX 时钟不同源，所以<b>数据永远不推进 since</b>：
 *       已在线时不改任何状态（只记 lastSeen）；离线时只有数据时间超过 {@code since + 宽限期} 才翻转为在线。</li>
 * </ul>
 */
@Component
public class PresenceStore {

    /**
     * 数据让离线设备重新上线所需超过的宽限期（毫秒）。断连事件前后设备仍可能有在途数据，且 transport 与 EMQX
     * 时钟存在偏差，宽限期内（含边界）的数据不会让刚断连的设备复活。所有者接受 10 秒。
     */
    public static final long DATA_ONLINE_GRACE_MILLIS = 10_000L;

    private static final ClassPathResource APPLY_RESOURCE = new ClassPathResource("redis/presence-apply.lua");
    private static final RedisScript<Long> APPLY = RedisScript.of(APPLY_RESOURCE, Long.class);

    private final StringRedisTemplate redis;

    public PresenceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 设备在线状态的 Redis key。 */
    public static String key(String deviceKey) {
        return "conduit:presence:" + deviceKey;
    }

    /**
     * 应用一次 EMQX 上下线事件（来源 event）。若 Redis 中已有更晚的事件（since 大于 eventTime）则忽略，
     * 防止快速断连重连时旧事件后到覆盖新状态；例外：当前记录由数据推断（source=data）时 since 不可信，事件总是生效。
     * 上线事件的 clientIp 为空时保留原有 IP，下线事件按传入值写入。
     *
     * @return 是否真正写入（false 表示事件过期被丢弃）
     */
    public boolean apply(String deviceKey, boolean online, long eventTime, String clientIp) {
        return run(deviceKey, online, eventTime, clientIp == null ? "" : clientIp, "event");
    }

    /**
     * 根据设备上报的数据把设备标记为在线（来源 data），ts 为 transport 接收时间。数据永远不推进 since：
     * 没有记录 → 置在线、since=ts；已在线 → 不改状态（只刷新 lastSeen）；已离线 → 仅当 {@code ts > since + 宽限期}
     * （见 {@link #DATA_ONLINE_GRACE_MILLIS}）才翻转为在线并令 since=ts。不会改动 clientIp。
     *
     * @return 是否改变了在线状态（false 表示无变化或被宽限期挡下）
     */
    public boolean applyData(String deviceKey, long ts) {
        return run(deviceKey, true, ts, "", "data");
    }

    /**
     * 批量版 {@link #applyData}：一次 pipeline 往返处理多台设备，语义与逐个调用相同。
     *
     * @param lastSeenByDevice deviceKey → 数据接收时间（毫秒）
     */
    public void applyDataBatch(Map<String, Long> lastSeenByDevice) {
        if (lastSeenByDevice.isEmpty()) {
            return;
        }
        byte[] script = scriptSource();
        byte[] grace = Long.toString(DATA_ONLINE_GRACE_MILLIS).getBytes(StandardCharsets.UTF_8);
        redis.executePipelined((RedisCallback<Object>) connection -> {
            lastSeenByDevice.forEach((dk, ts) -> connection.scriptingCommands().eval(script, ReturnType.INTEGER, 1,
                    key(dk).getBytes(StandardCharsets.UTF_8), "true".getBytes(StandardCharsets.UTF_8),
                    Long.toString(ts).getBytes(StandardCharsets.UTF_8), new byte[0],
                    "data".getBytes(StandardCharsets.UTF_8), grace));
            return null;
        });
    }

    private boolean run(String deviceKey, boolean online, long time, String clientIp, String source) {
        Long applied = redis.execute(APPLY, List.of(key(deviceKey)), Boolean.toString(online), Long.toString(time),
                clientIp, source, Long.toString(DATA_ONLINE_GRACE_MILLIS));
        return applied != null && applied == 1L;
    }

    private static byte[] scriptSource() {
        try {
            return APPLY_RESOURCE.getContentAsByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 查询单个设备的在线状态；从未收到过上下线事件时为 empty。 */
    public Optional<Presence> get(String deviceKey) {
        return Optional.ofNullable(toPresence(redis.opsForHash().entries(key(deviceKey))));
    }

    /** 管道批量查询（用于设备列表）；返回结果只包含有状态记录的设备，没有记录的设备不在 map 中。 */
    public Map<String, Presence> getAll(List<String> deviceKeys) {
        if (deviceKeys.isEmpty()) {
            return Map.of();
        }
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String dk : deviceKeys) {
                connection.hashCommands().hGetAll(key(dk).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        Map<String, Presence> presence = new LinkedHashMap<>();
        for (int i = 0; i < deviceKeys.size(); i++) {
            @SuppressWarnings("unchecked")
            Presence p = toPresence((Map<Object, Object>) results.get(i));
            if (p != null) {
                presence.put(deviceKeys.get(i), p);
            }
        }
        return presence;
    }

    /** 删除设备的在线状态记录（设备被删除时清理）。 */
    public void delete(String deviceKey) {
        redis.delete(key(deviceKey));
    }

    /** Redis hash 转 Presence；hash 为空或不存在返回 null。 */
    private static Presence toPresence(Map<Object, Object> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return new Presence(
                Boolean.parseBoolean((String) hash.get("online")),
                Long.parseLong((String) hash.get("since")),
                (String) hash.getOrDefault("clientIp", ""));
    }
}
