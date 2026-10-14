package dev.conduit.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class SimulatorApplication {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private SimulatorApplication() {
    }

    /**
     * 失败时显式 System.exit(1)：未连上的 HiveMQ 客户端的自动重连线程是非守护线程，
     * 仅抛出异常 JVM 不会退出，容器的 restart: on-failure 也就无从触发。
     */
    public static void main(String[] args) {
        int status = 0;
        try {
            run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = 1;
        } catch (RuntimeException e) {
            log.error("Simulator failed", e);
            status = 1;
        }
        System.exit(status);
    }

    private static void run() throws InterruptedException {
        SimulatorConfig config = SimulatorConfig.fromEnv(System.getenv());
        ConduitApiClient api = new ConduitApiClient(config.apiUrl());
        loginWithRetry(api, config);

        List<DeviceCredentials> devices = DemoProvisioner.provision(api, config.productName(), config.deviceCount());
        log.info("Provisioned {} devices under product '{}'", devices.size(), config.productName());

        new DeviceSimulator(config.mqttHost(), config.mqttPort(), config.interval(), config.messagesPerDevice())
                .run(devices);
    }

    /** 一键启动时 server 可能还没就绪，最多等 2 分钟。 */
    private static void loginWithRetry(ConduitApiClient api, SimulatorConfig config) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                api.login(config.adminUsername(), config.adminPassword());
                return;
            } catch (RuntimeException e) {
                if (attempt >= 60) {
                    throw e;
                }
                log.info("Waiting for Conduit API ({}): {}", attempt, e.getMessage());
                Thread.sleep(2_000);
            }
        }
    }
}
