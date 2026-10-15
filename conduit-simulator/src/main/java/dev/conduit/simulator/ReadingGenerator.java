package dev.conduit.simulator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** 生成三相电表读数：电压围绕 220V、电流围绕 5A 波动，电能按功率累加。 */
public class ReadingGenerator {

    private final Random random;
    private double energyKwh;

    public ReadingGenerator(long seed) {
        this.random = new Random(seed);
        this.energyKwh = 1000 + random.nextInt(9000);
    }

    /**
     * 生成下一次读数：三相电压 Ua/Ub/Uc、电流 Ia/Ib/Ic 以及累计正向电能 EPimp（kWh，严格递增）。
     *
     * @param intervalSeconds 距上次读数的秒数，用于按功率累加电能
     */
    public Map<String, Object> next(double intervalSeconds) {
        Map<String, Object> reading = new LinkedHashMap<>();
        double powerW = 0;
        for (String phase : new String[]{"a", "b", "c"}) {
            double voltage = round(clamp(220 + random.nextGaussian() * 1.5, 211, 229), 1);
            double current = round(clamp(5 + random.nextGaussian() * 0.8, 0.5, 9.5), 2);
            reading.put("U" + phase, voltage);
            reading.put("I" + phase, current);
            powerW += voltage * current;
        }
        // 保证严格递增：即使功率极小也至少增加 0.001 kWh
        energyKwh += Math.max(powerW / 1000 * intervalSeconds / 3600, 0.001);
        reading.put("EPimp", round(energyKwh, 3));
        return reading;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(v * scale) / scale;
    }
}
