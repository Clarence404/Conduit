package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ConnectRetry 的单元测试：失败若干次后成功则继续、全部失败才抛出、WARN 频率、中断立即停止。 */
class ConnectRetryTest {

    final List<Duration> sleeps = new ArrayList<>();
    final AtomicInteger attempts = new AtomicInteger();

    private ConnectRetry retry(int maxAttempts) {
        return new ConnectRetry(maxAttempts, Duration.ofSeconds(2), sleeps::add);
    }

    @Test
    void defaultsMatchTheSpec() {
        assertThat(ConnectRetry.MAX_ATTEMPTS).isEqualTo(15);
        assertThat(ConnectRetry.DELAY).isEqualTo(Duration.ofSeconds(2));
        assertThat(ConnectRetry.WARN_EVERY).isEqualTo(5);
    }

    @Test
    void returnsTheFirstSuccessAfterFailures() throws Exception {
        String result = retry(15).call("device dk", () -> {
            if (attempts.incrementAndGet() <= 3) {
                throw new ExecutionException(new ConnectException("Connection refused"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(4);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void givesUpAfterMaxAttemptsWithTheLastCause() {
        assertThatThrownBy(() -> retry(15).call("device dk", () -> {
            throw new TimeoutException("t" + attempts.incrementAndGet());
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot connect device dk after 15 attempts")
                .hasRootCauseMessage("t15");
        assertThat(attempts).hasValue(15);
        assertThat(sleeps).hasSize(14);
    }

    @Test
    void interruptionStopsRetryingImmediately() {
        assertThatThrownBy(() -> retry(15).call("device dk", () -> {
            attempts.incrementAndGet();
            throw new InterruptedException("stop");
        })).isInstanceOf(InterruptedException.class);
        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void warnsOnEveryFifthFailure() {
        assertThat(ConnectRetry.shouldWarn(1)).isFalse();
        assertThat(ConnectRetry.shouldWarn(4)).isFalse();
        assertThat(ConnectRetry.shouldWarn(5)).isTrue();
        assertThat(ConnectRetry.shouldWarn(6)).isFalse();
        assertThat(ConnectRetry.shouldWarn(10)).isTrue();
    }

    @Test
    void describesTheCauseOfExecutionExceptions() {
        assertThat(ConnectRetry.describe(new ExecutionException(new ConnectException("Connection refused"))))
                .isEqualTo("ConnectException: Connection refused");
        assertThat(ConnectRetry.describe(new TimeoutException("slow"))).isEqualTo("TimeoutException: slow");
        assertThat(ConnectRetry.describe(new TimeoutException())).isEqualTo("TimeoutException");
        assertThat(ConnectRetry.describe(new ExecutionException(new TimeoutException()))).isEqualTo("TimeoutException");
    }

    /** 记录一次日志调用：级别（方法名）与是否带 Throwable 参数。 */
    private record LogEvent(String level, boolean hasThrowable) {
    }

    /** 用动态代理实现 slf4j Logger，记录所有 warn/error/info 调用。 */
    private static Logger recordingLogger(List<LogEvent> events) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("warn") || name.equals("error") || name.equals("info")) {
                        // log.warn(fmt, a, b, c, d) 走可变参数重载：末尾是 Object[]，需要展开后再检查
                        List<Object> flat = new ArrayList<>();
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Object[] array) {
                                    flat.addAll(java.util.Arrays.asList(array));
                                } else {
                                    flat.add(arg);
                                }
                            }
                        }
                        boolean throwable = flat.stream().anyMatch(a -> a instanceof Throwable);
                        events.add(new LogEvent(name, throwable));
                    }
                    Class<?> type = method.getReturnType();
                    return type == boolean.class ? Boolean.TRUE : null;
                });
    }

    @Test
    void logsTwoWarningsWithoutStackTracesFor14FailuresThenSuccess() throws Exception {
        List<LogEvent> events = new ArrayList<>();
        ConnectRetry retry = new ConnectRetry(15, Duration.ofSeconds(2), sleeps::add, recordingLogger(events));

        String result = retry.call("device dk", () -> {
            if (attempts.incrementAndGet() <= 14) {
                throw new ExecutionException(new ConnectException("Connection refused"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(events).filteredOn(e -> e.level().equals("warn")).hasSize(2)
                .noneMatch(LogEvent::hasThrowable);
        assertThat(events).noneMatch(e -> e.level().equals("error"));
    }
}
