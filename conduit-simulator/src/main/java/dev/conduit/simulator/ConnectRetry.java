package dev.conduit.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

/**
 * 设备首次连接 MQTT 的重试策略：间隔 2 秒、最多 15 次（约 30 秒）。每失败 5 次打印一条不带堆栈的 WARN，
 * 全部失败才抛出异常（由 SimulatorApplication 打印一次 ERROR 并退出）。
 * 解决重新部署时 EMQX 与 server 同时重启、EMQX 的鉴权回调尚未就绪导致模拟器首次连接即退出的问题。
 */
final class ConnectRetry {

    static final int MAX_ATTEMPTS = 15;
    static final Duration DELAY = Duration.ofSeconds(2);
    static final int WARN_EVERY = 5;

    private static final Logger log = LoggerFactory.getLogger(ConnectRetry.class);

    /** 一次连接尝试。 */
    @FunctionalInterface
    interface Attempt<T> {
        T run() throws Exception;
    }

    /** 两次尝试之间的等待；测试中替换为只记录不睡眠的实现。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int maxAttempts;
    private final Duration delay;
    private final Sleeper sleeper;

    ConnectRetry() {
        this(MAX_ATTEMPTS, DELAY, d -> Thread.sleep(d.toMillis()));
    }

    ConnectRetry(int maxAttempts, Duration delay, Sleeper sleeper) {
        this.maxAttempts = maxAttempts;
        this.delay = delay;
        this.sleeper = sleeper;
    }

    /**
     * 执行 attempt，失败则等待后重试。
     *
     * @param target  日志与异常中描述连接目标的文字，如 "device dk-1 to emqx:1883"
     * @param attempt 一次连接尝试，失败时抛出异常
     * @return 第一次成功的结果
     * @throws IllegalStateException 达到最大次数仍失败；cause 为最后一次的异常
     * @throws InterruptedException  尝试或等待中被中断，立即抛出，不再重试
     */
    <T> T call(String target, Attempt<T> attempt) throws InterruptedException {
        for (int n = 1; ; n++) {
            try {
                return attempt.run();
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                String reason = describe(e);
                if (n >= maxAttempts) {
                    throw new IllegalStateException("Cannot connect " + target + " after " + n + " attempts: "
                            + reason, e);
                }
                if (shouldWarn(n)) {
                    log.warn("Cannot connect {} yet (attempt {}/{}): {}", target, n, maxAttempts, reason);
                }
                sleeper.sleep(delay);
            }
        }
    }

    /** 第 failedAttempts 次失败后是否打印 WARN：每 5 次一条。 */
    static boolean shouldWarn(int failedAttempts) {
        return failedAttempts % WARN_EVERY == 0;
    }

    /** 失败原因的简短描述；ExecutionException 取其 cause，便于看出真正的连接错误。 */
    static String describe(Exception e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
