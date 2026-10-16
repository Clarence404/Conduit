package dev.conduit.simulator;

import java.util.Map;

/** 一台模拟设备的读数来源：每次上报调用一次，返回测点标识到取值的映射。 */
@FunctionalInterface
public interface ReadingSource {

    /**
     * 生成一次读数。
     *
     * @param epochMillis     上报时间（设备时间戳，毫秒）
     * @param intervalSeconds 距上次上报的秒数，用于累计量积分
     */
    Map<String, Object> next(long epochMillis, double intervalSeconds);
}
