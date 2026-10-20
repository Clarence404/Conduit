package dev.conduit.server.pipeline;

import dev.conduit.server.telemetry.TelemetryUnavailableException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backoff 单元测试：可恢复错误原地重试、不可恢复错误立即抛出、有上限时抛出最后一次异常、被中断时中止并保留中断标志。 */
class BackoffTest {

    private static final Duration FAST = Duration.ofMillis(1);

    private static Backoff capped(int maxAttempts) {
        return new Backoff(FAST, Duration.ofMillis(5), maxAttempts);
    }

    private static TelemetryUnavailableException down() {
        return new TelemetryUnavailableException(new RuntimeException("down"));
    }

    @Test
    void retriesRecoverableFailuresUntilTheActionSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Backoff backoff = Backoff.unlimited(FAST, Duration.ofMillis(5));

        String result = backoff.run("influx write", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw down();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void rethrowsNonRecoverableFailuresWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> capped(5).run("lookup", () -> {
            attempts.incrementAndGet();
            throw new BadSqlGrammarException("q", "select", new SQLException("x"));
        })).isInstanceOf(BadSqlGrammarException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void rethrowsTheLastFailureOnceTheAttemptLimitIsReached() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> capped(3).run("influx write", () -> {
            attempts.incrementAndGet();
            throw down();
        })).isInstanceOf(TelemetryUnavailableException.class);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void treatsADatabaseOutageWrappedByMyBatisAsRecoverable() {
        assertThat(Backoff.isRecoverable(new MyBatisSystemException(
                new PersistenceException(new CannotGetJdbcConnectionException("down"))))).isTrue();
        assertThat(Backoff.isRecoverable(new BadSqlGrammarException("q", "select", new SQLException("x")))).isFalse();
    }

    @Test
    void anInterruptedWaitAbortsAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> capped(5).run("influx write", () -> {
                throw down();
            })).isInstanceOf(ProcessingAbortedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
