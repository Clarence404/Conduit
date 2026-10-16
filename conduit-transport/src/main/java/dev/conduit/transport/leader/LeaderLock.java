package dev.conduit.transport.leader;

/**
 * 选主锁：同一时刻至多一个 transport 实例持有。实现须保证只有持锁实例能续期与释放；
 * 三个操作在 Redis 不可用时抛 {@link org.springframework.dao.DataAccessException}，由 {@link LeaderElection} 按续期失败处理。
 */
public interface LeaderLock {

    /** 本实例的 id（锁的值），日志与健康检查中用于区分实例。 */
    String instanceId();

    /** 尝试抢锁；锁已被任何实例（包括本实例）持有时返回 false。 */
    boolean tryAcquire();

    /** 续期；锁不存在或已被其他实例持有时返回 false。 */
    boolean renew();

    /** 释放；锁不存在或已被其他实例持有时返回 false，不删除。 */
    boolean release();
}
