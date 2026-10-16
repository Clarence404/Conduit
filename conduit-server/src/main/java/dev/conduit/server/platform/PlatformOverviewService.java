package dev.conduit.server.platform;

import dev.conduit.server.platform.PlatformDtos.PlatformOverview;
import dev.conduit.server.platform.PlatformDtos.TenantStat;
import dev.conduit.server.platform.TenantStats.TenantCounts;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** 平台概况：租户数（启用/停用）、租户用户数、产品数、设备数、在线数与设备最多的 5 个租户（跨租户只读统计）。 */
@Service
public class PlatformOverviewService {

    static final int TOP_TENANTS = 5;

    private static final Logger log = LoggerFactory.getLogger(PlatformOverviewService.class);

    private final TenantMapper tenants;
    private final TenantStats stats;

    public PlatformOverviewService(TenantMapper tenants, TenantStats stats) {
        this.tenants = tenants;
        this.stats = stats;
    }

    /**
     * 组装平台概况。
     *
     * @return 概况；Redis 不可用时 onlineCount 与 topTenants[].onlineCount 为 null
     */
    public PlatformOverview overview() {
        long start = System.nanoTime();
        List<Tenant> all = tenants.selectList(null);
        int active = (int) all.stream().filter(t -> Tenant.ACTIVE.equals(t.getStatus())).count();
        TenantStats.Snapshot snapshot = stats.snapshot();
        List<TenantStat> top = all.stream()
                .map(t -> {
                    TenantCounts c = snapshot.of(t.getId());
                    return new TenantStat(t.getCode(), t.getName(), c.deviceCount(), c.onlineCount());
                })
                .sorted(Comparator.comparingInt(TenantStat::deviceCount).reversed().thenComparing(TenantStat::code))
                .limit(TOP_TENANTS)
                .toList();
        PlatformOverview view = new PlatformOverview(all.size(), active, all.size() - active, snapshot.userCount(),
                snapshot.productCount(), snapshot.deviceCount(), snapshot.onlineCount(), top);
        log.debug("Platform overview: tenants={} users={} devices={} online={} elapsedMs={}", all.size(),
                view.userCount(), view.deviceCount(), view.onlineCount(), (System.nanoTime() - start) / 1_000_000);
        return view;
    }
}
