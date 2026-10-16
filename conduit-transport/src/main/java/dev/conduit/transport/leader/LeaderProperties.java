package dev.conduit.transport.leader;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * transport 选主配置（前缀 conduit.transport.leader，V0.2-R spec 3.3）。
 *
 * @param key             Redis 锁键，所有 transport 实例共用
 * @param ttl             锁的过期时间：主实例崩溃后最长经过这么久备实例才能接管
 * @param renewInterval   主实例续期间隔，必须短于 ttl
 * @param acquireInterval 备实例尝试抢锁的间隔
 */
@ConfigurationProperties(prefix = "conduit.transport.leader")
public record LeaderProperties(
        @DefaultValue("conduit:transport:leader") String key,
        @DefaultValue("15s") Duration ttl,
        @DefaultValue("5s") Duration renewInterval,
        @DefaultValue("2s") Duration acquireInterval) {

    /** 校验间隔：续期间隔必须为正且短于 ttl，抢锁间隔必须为正，否则启动失败。 */
    public LeaderProperties {
        if (renewInterval.isZero() || renewInterval.isNegative() || renewInterval.compareTo(ttl) >= 0) {
            throw new IllegalStateException("conduit.transport.leader.renew-interval (" + renewInterval
                    + ") must be positive and shorter than ttl (" + ttl + ")");
        }
        if (acquireInterval.isZero() || acquireInterval.isNegative()) {
            throw new IllegalStateException("conduit.transport.leader.acquire-interval (" + acquireInterval
                    + ") must be positive");
        }
    }
}
