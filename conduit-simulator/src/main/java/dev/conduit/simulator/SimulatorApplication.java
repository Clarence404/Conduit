package dev.conduit.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 设备模拟器入口（演示/压测用，独立于平台进程）：通过 REST 准备演示产品、物模型与设备；配置了平台管理员密码时，
 * 再发现各租户有物模型的产品与启用设备（{@link SimulationPlanner}）；然后用各设备凭据经 MQTT 连接 EMQX 周期上报，
 * 驱动整条 设备 -> EMQX -> transport -> 总线 -> server 链路。
 */
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

    /**
     * 主流程：读配置、登录（等待 server 就绪）、准备演示设备、缺少演示属性时合并导入演示物模型、
     * 发现各租户的模拟设备，然后开始模拟上报。有限模式下返回即表示全部发送成功。
     */
    private static void run() throws InterruptedException {
        SimulatorConfig config = SimulatorConfig.load();
        ConduitApiClient api = new ConduitApiClient(config.apiUrl());
        loginWithRetry(api, config);

        PayloadFormat format = config.payloadFormat();
        List<DeviceCredentials> demo = DemoProvisioner.provision(api, config.productName(), config.deviceCount(),
                format.codec());
        log.info("Provisioned {} devices under product '{}'", demo.size(), config.productName());
        if (!demo.isEmpty() && DemoProvisioner.ensureThingModel(api, demo.getFirst().productKey())) {
            log.info("Imported demo thing model into product '{}'", config.productName());
        }

        // 演示产品的物模型含枚举 st，附带运行状态；e2e 等无物模型的产品不能发 st（见 DeviceSimulator 构造器说明）
        List<SimulatedDevice> devices = new ArrayList<>();
        demo.forEach(d -> devices.add(SimulatedDevice.demo(d, format, true)));
        // 演示产品 Key 与设备数无关（deviceCount=0 时也要让发现跳过它）
        Set<String> demoProductKeys = Set.of(api.findOrCreateProduct(config.productName(), format.codec()));
        devices.addAll(SimulationPlanner.tenantDevices(config, () -> new ConduitApiClient(config.apiUrl()),
                demoProductKeys));

        new DeviceSimulator(config.mqttHost(), config.mqttPort(), config.interval(), config.messagesPerDevice(),
                DeviceSimulator.DEFAULT_COMPLETION_TIMEOUT, format, true).runDevices(devices);
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
