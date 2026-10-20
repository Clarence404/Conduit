package dev.conduit.server.pipeline;

import dev.conduit.server.telemetry.TelemetryUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 下游暂时不可用时的原地指数退避重试：只有可恢复错误才重试，延迟从 initial 起翻倍、封顶 max。
 * 流水线用无上限版本（消息保持未 ACK 比丢数据好）；重放用有上限版本，超限后把异常抛给调用方，由它把任务置为 FAILED。
 */
public class Backoff {

    /** maxAttempts 取该值表示不限次数。 */
    public static final int UNLIMITED = 0;

    private static final Logger log = LoggerFactory.getLogger(Backoff.class);
    private static final int MAX_CAUSE_DEPTH = 16;

    private final Duration initial;
    private final Duration max;
    private final int maxAttempts;

    /**
     * @param initial     首次重试前的等待时间
     * @param max         等待时间上限
     * @param maxAttempts 最多尝试次数（含第一次），{@link #UNLIMITED} 表示不限
     */
    public Backoff(Duration initial, Duration max, int maxAttempts) {
        this.initial = initial;
        this.max = max;
        this.maxAttempts = maxAttempts;
    }

    /** 不限次数的退避，流水线消费用：只要消息没处理完就一直等下游恢复。 */
    public static Backoff unlimited(Duration initial, Duration max) {
        return new Backoff(initial, max, UNLIMITED);
    }

    /**
     * 原地执行 action，仅可恢复错误（见 {@link #isRecoverable}）才退避重试。
     *
     * @param step   步骤名，仅用于日志
     * @param action 要执行的动作；无返回值时返回 null
     * @return action 的返回值
     * @throws ProcessingAbortedException 等待期间线程被中断（会恢复中断标志）
     * @throws RuntimeException           不可恢复的异常原样抛出；达到 maxAttempts 后抛出最后一次可恢复异常
     */
    public <T> T run(String step, Supplier<T> action) {
        long delay = initial.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.debug("{} recovered after {} attempts", step, attempt);
                }
                return result;
            } catch (RuntimeException e) {
                if (!isRecoverable(e)) {
                    throw e;
                }
                if (maxAttempts != UNLIMITED && attempt >= maxAttempts) {
                    log.warn("{} still unavailable after {} attempts, giving up: {}", step, attempt, e.getMessage());
                    throw e;
                }
                log.warn("{} unavailable (attempt {}), retrying in {} ms: {}", step, attempt, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ProcessingAbortedException("Interrupted while retrying " + step);
                }
                delay = Math.min(delay * 2, max.toMillis());
            }
        }
    }

    /**
     * 仅暂时性故障值得原地重试；其余（语法错误、完整性冲突、WRONGTYPE 等）重试也不会成功。
     * 沿 cause 链查找：MyBatis 会把取连接失败（CannotGetJdbcConnectionException）包装成 MyBatisSystemException，
     * 只看顶层会把数据库宕机误判为不可恢复。
     */
    public static boolean isRecoverable(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TelemetryUnavailableException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
