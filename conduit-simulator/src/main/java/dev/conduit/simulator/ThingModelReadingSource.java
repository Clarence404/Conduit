package dev.conduit.simulator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按产品物模型为一台设备生成读数（spec 5.2）：每个可模拟的属性一个 {@link PointGenerator}，TEXT 等跳过；
 * 先生成瞬时量，再按本轮功率/流量积分累计量。日曲线按 zone 的本地时间计算。
 */
public final class ThingModelReadingSource implements ReadingSource {

    private final ZoneId zone;
    private final List<PointGenerator> instant = new ArrayList<>();
    private final List<PointGenerator> totals = new ArrayList<>();
    private final PointGenerator.Nominals nominals;

    /**
     * @param properties 产品物模型的属性定义
     * @param deviceKey  设备 Key（随机数种子，不同设备的序列不同）
     * @param zone       计算日曲线所用的时区
     */
    public ThingModelReadingSource(List<PropertyDef> properties, String deviceKey, ZoneId zone) {
        this.zone = zone;
        double pv = 0;
        double load = 0;
        double flow = 0;
        for (PropertyDef p : properties) {
            if (!PointClassifier.simulated(p)) {
                continue;
            }
            PointGenerator g = new PointGenerator(p, deviceKey);
            (g.cumulative() ? totals : instant).add(g);
            switch (g.kind) {
                case PV_POWER -> pv += g.nominalKw();
                case LOAD -> load += g.nominalKw();
                case FLOW -> flow += g.nominalValue();
                default -> {
                }
            }
        }
        this.nominals = new PointGenerator.Nominals(pv > 0 ? pv : 50, load > 0 ? load : 30, flow > 0 ? flow : 30);
    }

    /** 会生成取值的测点数。 */
    public int pointCount() {
        return instant.size() + totals.size();
    }

    @Override
    public Map<String, Object> next(long epochMillis, double intervalSeconds) {
        ZonedDateTime time = Instant.ofEpochMilli(epochMillis).atZone(zone);
        double hour = time.getHour() + time.getMinute() / 60.0 + time.getSecond() / 3600.0;
        PointGenerator.Rates rates = new PointGenerator.Rates();
        Map<String, Object> reading = new LinkedHashMap<>();
        for (PointGenerator g : instant) {
            reading.put(g.def.identifier(), g.next(epochMillis, hour, rates));
        }
        for (PointGenerator g : totals) {
            reading.put(g.def.identifier(), g.accumulate(hour, time.toLocalDate(), intervalSeconds, rates, nominals));
        }
        return reading;
    }
}
