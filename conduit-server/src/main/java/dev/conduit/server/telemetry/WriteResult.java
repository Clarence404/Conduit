package dev.conduit.server.telemetry;

import java.util.List;

/**
 * 一批遥测写入的结果：只列出被拒绝的点，其余视为已写入。
 * @param rejected 被拒的点，按原批次下标升序
 */
public record WriteResult(List<Rejected> rejected) {

    /**
     * 被拒的点：index 为其在原批次中的下标（从 0 开始），message 为原因。
     */
    public record Rejected(int index, String message) {
    }

    /**
     * 全部写入成功的结果。
     */
    public static WriteResult ok() {
        return new WriteResult(List.of());
    }
}
