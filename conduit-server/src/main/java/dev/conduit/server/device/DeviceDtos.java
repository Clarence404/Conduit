package dev.conduit.server.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 设备接口的请求与响应对象集合。
 */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record CreateDeviceRequest(@NotBlank String productKey, @NotBlank @Size(max = 128) String name) {
    }

    public record UpdateDeviceRequest(@NotBlank @Size(max = 128) String name,
                                      @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status) {
    }

    /**
     * 设备响应视图。
     * @param online 是否在线（来自 Redis 在线状态）
     * @param lastReportAt 最近一次上报时间（毫秒），从未上报为 null
     */
    public record DeviceView(String deviceKey, String name, String productKey, String status,
                             boolean online, Long lastReportAt, OffsetDateTime createdAt) {
    }

    /**
     * 创建设备的响应；deviceSecret 为明文密钥，仅此一次返回，之后无法再查看。
     */
    public record DeviceCreated(DeviceView device, String deviceSecret) {
    }

    /**
     * 重置密钥的响应；deviceSecret 为新的明文密钥，仅此一次返回。
     */
    public record SecretView(String deviceKey, String deviceSecret) {
    }
}
