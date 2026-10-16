package dev.conduit.transport.leader;

import dev.conduit.transport.support.TestBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** RedisLeaderLock 集成测试（真实 Redis）：只有一个实例抢到锁、只有持锁者能续期与释放、锁过期后他人接管。 */
class RedisLeaderLockIT {

    final StringRedisTemplate redis = TestBroker.redis();
    final String key = "it:leader:" + UUID.randomUUID();
    final Duration ttl = Duration.ofSeconds(2);
    final RedisLeaderLock a = new RedisLeaderLock(redis, key, ttl, "instance-a");
    final RedisLeaderLock b = new RedisLeaderLock(redis, key, ttl, "instance-b");

    @AfterEach
    void removeKey() {
        redis.delete(key);
    }

    @Test
    void onlyOneInstanceAcquiresAndTheValueIsItsId() {
        assertThat(a.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();

        assertThat(redis.opsForValue().get(key)).isEqualTo("instance-a");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
    }

    @Test
    void theHolderReacquiresItsOwnLockAndRefreshesTheTtlWhileOthersStayRefused() throws Exception {
        assertThat(a.tryAcquire()).isTrue();
        Thread.sleep(700);
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);

        assertThat(a.tryAcquire()).as("the holder re-acquires its own lock").isTrue();

        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isGreaterThan(before);
        assertThat(redis.opsForValue().get(key)).isEqualTo("instance-a");
        assertThat(b.tryAcquire()).as("another instance is still refused").isFalse();
    }

    @Test
    void renewExtendsOnlyTheHoldersLock() throws Exception {
        assertThat(a.tryAcquire()).isTrue();
        Thread.sleep(1200);

        assertThat(a.renew()).isTrue();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isGreaterThan(1500L);
        assertThat(b.renew()).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo("instance-a");
    }

    @Test
    void anExpiredLockGoesToAnotherInstanceAndTheOldHolderCannotRenewIt() {
        assertThat(a.tryAcquire()).isTrue();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(b::tryAcquire);

        assertThat(a.renew()).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo("instance-b");
    }

    @Test
    void releaseDeletesOnlyTheHoldersLock() {
        assertThat(a.tryAcquire()).isTrue();

        assertThat(b.release()).isFalse();
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(a.release()).isTrue();
        assertThat(b.tryAcquire()).as("a released lock can be taken at once").isTrue();
    }

    @Test
    void instanceIdsCarryTheProcessIdAndDifferPerCall() {
        String id = RedisLeaderLock.newInstanceId();

        assertThat(id).contains("-" + ProcessHandle.current().pid() + "-");
        assertThat(RedisLeaderLock.newInstanceId()).isNotEqualTo(id);
    }
}
