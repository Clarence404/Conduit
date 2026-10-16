package dev.conduit.simulator;

import java.util.Map;

/**
 * 一台要模拟的设备：连接凭据、上报格式（按产品解码器）与读数来源（演示电表或按物模型生成）。
 *
 * @param credentials MQTT 凭据（含产品 Key，用于拼 topic）
 * @param format      上报载荷格式
 * @param readings    每次上报的读数来源
 */
public record SimulatedDevice(DeviceCredentials credentials, PayloadFormat format, ReadingSource readings) {

    /**
     * 演示产品的设备：三相电表读数（{@link ReadingGenerator}，种子为 deviceKey 的哈希），
     * operatingState 为 true 时附带运行状态 st（见 {@link OperatingState}）。
     */
    public static SimulatedDevice demo(DeviceCredentials credentials, PayloadFormat format, boolean operatingState) {
        ReadingGenerator generator = new ReadingGenerator(credentials.deviceKey().hashCode());
        return new SimulatedDevice(credentials, format, (ts, intervalSeconds) -> {
            Map<String, Object> reading = generator.next(intervalSeconds);
            if (operatingState) {
                reading.put(OperatingState.IDENTIFIER, OperatingState.at(credentials.deviceKey(), ts));
            }
            return reading;
        });
    }
}
