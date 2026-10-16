package dev.conduit.transport.leader;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的选主锁（V0.2-R spec 3.3）：值为实例 id；抢锁用 Lua（键不存在则 SET PX ttl，键已是本实例 id 则 PEXPIRE，
 * 因此 Redis 抖动后本实例可重新拿回自己的锁而不必空等一个 ttl）；续期与释放用 Lua 先比对 id 再
 * PEXPIRE / DEL，保证锁过期并被其他实例抢到后，旧主实例的续期与释放都不生效。
 */
public class RedisLeaderLock implements LeaderLock {

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if v == ARGV[1] then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end "
                    + "if not v then redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) return 1 end "
                    + "return 0", Long.class);
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

    /** 原子抢锁：键已是本实例 id 则续 ttl 并视为抢到；键不存在则写入本实例 id 并设置 ttl；被其他实例持有则返回 false。 */
    @Override
    public boolean tryAcquire() {
        Long result = redis.execute(ACQUIRE, List.of(key), instanceId, Long.toString(ttl.toMillis()));
        return Long.valueOf(1).equals(result);
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
