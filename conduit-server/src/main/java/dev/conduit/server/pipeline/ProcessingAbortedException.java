package dev.conduit.server.pipeline;

/**
 * 处理被中断（worker 停止，或重放任务所在线程被中断）：中止当前这批，已读取的投递保持未 ACK，
 * 之后由认领机制重新投递；重放时由调用方把任务置为 CANCELLED。
 */
public class ProcessingAbortedException extends RuntimeException {

    public ProcessingAbortedException(String message) {
        super(message);
    }
}
