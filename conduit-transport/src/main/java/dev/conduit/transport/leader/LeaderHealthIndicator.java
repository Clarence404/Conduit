package dev.conduit.transport.leader;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 健康检查组件 leader（/actuator/health 的 components.leader）：details 含 role（LEADER / STANDBY）、instanceId
 * 与 connected（主实例的协议适配器是否已连接）。备实例同样健康，状态恒为 UP。
 */
public class LeaderHealthIndicator implements HealthIndicator {

    private final LeaderElection election;

    public LeaderHealthIndicator(LeaderElection election) {
        this.election = election;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("role", election.role().name())
                .withDetail("instanceId", election.instanceId())
                .withDetail("connected", election.adaptersConnected())
                .build();
    }
}
