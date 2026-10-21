package dev.conduit.server.pipeline;

import java.util.Locale;

/**
 * 共用处理单元的调用来源，用作 conduit.pipeline.rejected_fields 指标的 source 标签：
 * 重放读的是历史数据且可对同一区间反复执行，必须与实时消费的计数分开。
 */
public enum IngestSource {
    /** 正常消费总线上的实时消息。 */
    LIVE,
    /** 遥测重放：回放流中的历史消息。 */
    REPLAY;

    /** 指标标签值（小写）。 */
    String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
