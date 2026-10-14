package dev.conduit.server.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record CreateDeviceRequest(@NotBlank String productKey, @NotBlank @Size(max = 128) String name) {
    }

    public record UpdateDeviceRequest(@NotBlank @Size(max = 128) String name,
                                      @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status) {
    }

    public record DeviceView(String deviceKey, String name, String productKey, String status,
                             boolean online, Long lastReportAt, OffsetDateTime createdAt) {
    }

    public record DeviceCreated(DeviceView device, String deviceSecret) {
    }

    public record SecretView(String deviceKey, String deviceSecret) {
    }
}
