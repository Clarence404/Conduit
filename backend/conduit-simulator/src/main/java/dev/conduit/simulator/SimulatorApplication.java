package dev.conduit.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class SimulatorApplication {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private SimulatorApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
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
