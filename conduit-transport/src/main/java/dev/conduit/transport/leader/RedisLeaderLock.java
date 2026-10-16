package dev.conduit.transport.leader;

import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的选主锁（V0.2-R spec 3.3）：值为实例 id；抢锁 SET NX PX ttl；续期与释放用 Lua 先比对 id 再
 * PEXPIRE / DEL，保证锁过期并被其他实例抢到后，旧主实例的续期与释放都不生效。
 */
public class RedisLeaderLock implements LeaderLock {

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
                    + "return 0", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final String key;
    private final Duration ttl;
    private final String instanceId;

    public RedisLeaderLock(StringRedisTemplate redis, String key, Duration ttl, String instanceId) {
        this.redis = redis;
        this.key = key;
        this.ttl = ttl;
        this.instanceId = instanceId;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    /** SET key id NX PX ttl：键不存在时写入本实例 id 并设置过期，返回是否写入成功。 */
    @Override
    public boolean tryAcquire() {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = instanceId.getBytes(StandardCharsets.UTF_8);
        Boolean ok = redis.execute((RedisCallback<Boolean>) c -> c.stringCommands()
                .set(k, v, Expiration.milliseconds(ttl.toMillis()), SetOption.ifAbsent()));
        return Boolean.TRUE.equals(ok);
    }

    /** 值仍为本实例 id 时把过期时间重置为 ttl。 */
    @Override
    public boolean renew() {
        Long result = redis.execute(RENEW, List.of(key), instanceId, Long.toString(ttl.toMillis()));
        return Long.valueOf(1).equals(result);
    }

    /** 值仍为本实例 id 时删除键，备实例可立即接管。 */
    @Override
    public boolean release() {
        Long result = redis.execute(RELEASE, List.of(key), instanceId);
        return Long.valueOf(1).equals(result);
    }

    /**
     * 生成实例 id：主机名-进程号-8 位随机十六进制。主机名取不到时用 unknown-host。
     * 同一主机上的多个进程、同一进程的多次调用都得到不同的 id。
     */
    public static String newInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
