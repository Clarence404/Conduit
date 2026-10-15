# Conduit Frontend Experience Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the default-looking Element Plus screens into one consistent admin UI: a grouped, collapsible sidebar with light/dark styles, a top bar with breadcrumbs and an appearance switch, whole-app dark mode, a shared list kit, a device detail page with an info card and metric cards with sparklines, a new home overview backed by `GET /api/v1/overview`, and a redesigned login page.

**Architecture:** The backend gets three read-only additions. `DeviceStats` (device package) counts devices per product from PostgreSQL and online devices from Redis presence; it feeds the new `deviceCount`/`onlineCount` on `ProductView` and the new `overview` package (`OverviewService`, `DeadLetterReader`, `OverviewController`). `UserView` also gains `tenantName` for the top bar. On the frontend, a Pinia `ui` store persists appearance preferences in `localStorage` (`conduit.ui`), and `useTheme` toggles Element Plus's `dark` class on `<html>` and drives the ECharts theme. Route `meta` drives breadcrumbs, the document title and menu highlighting. `useTable` replaces the per-view paging, sequence and page-clamp code. The device detail page is rebuilt from small components (`DeviceInfoCard`, `ConnectionDialog`, `LatestValuesPanel`, `MetricCard`, `Sparkline`).

**Tech Stack:** Java 21, Spring Boot 3.5.6, MyBatis-Plus 3.5.12, PostgreSQL 16, Redis 7.4 (Streams), Testcontainers; Vue 3.5.42 + TypeScript + Element Plus 2.11.1 + `@element-plus/icons-vue` 2.3.1 + Pinia 2.3.1 + vue-router 4.5.0 + ECharts 6.1.0 + Vitest + @vue/test-utils.

**Governing spec:** `docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md` (behavior); repo-root `CLAUDE.md` (conventions, comment rules, maintenance rule).

## Global Constraints

**Operator-provided placeholders** (set by the operator in their shell; never write their real values into any committed file):

| Placeholder | Meaning |
|---|---|
| `$JAVA21_HOME` | Path of a JDK 21 installation |
| `<docker-host>` | Address of the (remote) Docker host that runs Testcontainers and the Compose stack |
| `$DOCKER_CLI` | Path of the `docker` CLI binary |
| `$COMPOSE_CLI` | Path of the standalone `docker-compose` binary (or `docker compose`) |

**Shell preamble**: run once per shell before any Maven, Docker or e2e command in this plan. All commands run from the repository root unless a step says otherwise.

```bash
export JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH"
export DOCKER_HOST="tcp://<docker-host>:2375" TESTCONTAINERS_HOST_OVERRIDE="<docker-host>"
```

**Command patterns** (the tasks use them verbatim):

- Single unit test class: `mvn -B -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
- Single integration test class: `mvn -B -pl <module> -am verify -Dtest=NONE -Dit.test=<Class> -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
- Full backend build: `mvn -B verify`
- Single frontend spec: `cd conduit-ui && npx vitest run <path-under-conduit-ui> ; cd ..`
- Frontend: `cd conduit-ui && npm test && npm run build ; cd ..` (`npm run build` runs `vue-tsc --noEmit` first, so it is also the type check)

**Project rules:**

- **Code comments (owner rule, applies to every code block in this plan):** (1) every class, interface, record and enum, every TS module and every Vue component gets a Chinese header comment saying what it is for: Javadoc on the Java type, a top-of-file comment in TS/CSS, and a comment at the top of `<script setup>` in Vue; (2) every key method or function gets a Chinese Javadoc/JSDoc stating what it does, its important parameters and return value, and any non-obvious behavior (exceptions, edge cases, side effects). "Key" means public/service/controller methods, pure policy functions and anything with branching logic. Trivial getters and one-line helpers are exempt. Test classes and spec files get a header comment too. Comments say "what and why", never restate the code, and are updated together with the code. These are the rules of `CLAUDE.md` §2.1; read `CLAUDE.md` before starting. Do not edit `CLAUDE.md` except in Task 8 Step 2, which its maintenance rule requires.
- **Git:** Conventional Commits. This repo commits with the author name only and an empty email: never run `git config user.email` and never pass an email. Commits and PRs carry no AI attribution lines (no `Co-Authored-By`, no "Generated with").
- **No secrets in tracked files:** no IP addresses, host names, local Windows paths, user names, passwords or tokens. Docs use `<docker-host>`. Local config (`deploy/.env`, `application-local.yml`, `simulator-local.properties`) stays untracked.
- **Versions (exact):** Java 21; Element Plus `2.11.1`; Vue `3.5.42`; ECharts `6.1.0`; `@element-plus/icons-vue` `2.3.1`. This plan adds **no** npm dependency: icons come from `@element-plus/icons-vue` (already a dependency), dark mode from `element-plus/theme-chalk/dark/css-vars.css` (shipped inside `element-plus`), the ECharts dark theme is built into `echarts/core`. If an executor finds a dependency truly unavoidable, it must be an exact version at least two weeks old, added with `--save-exact`, and justified in the commit message.
- **npm** runs on the developer machine only. The Docker host has no internet; deploy only via `deploy/build-local.sh`, which builds the jars and `dist` locally.
- **Tests:** `*Test` = unit (surefire), `*IT` = integration (failsafe, Testcontainers). Server integration tests extend `IntegrationTestBase`. Integration tests share one database and one Redis, so a test that asserts exact counts creates its own tenants (`createTenant`) and unique keys (`unique(...)`). Frontend specs use Vitest + `@vue/test-utils`, mock `@/api/*` modules and `@/utils/echarts`, and use `createTestRouter`/`settle` from `src/test/viewTestUtils.ts`.
- **Errors:** do not add class-level `@Validated` to controllers. Errors are `ProblemDetail`; throw `ApiException`. "Redis unavailable" means a `org.springframework.dao.DataAccessException` (for example `RedisConnectionFailureException`), the same check `DeviceService.bestEffort` uses.
- **UI conventions (CLAUDE.md §2.2):** all copy is Chinese; every paged table defaults to 10 per page with options 10/20/50/100 (`DEFAULT_PAGE_SIZE`, `PAGE_SIZES` in `utils/pagination.ts`); entries are prominent (「操作」 column buttons, breadcrumbs, product ↔ device links); detail-page tabs sync with query `tab` via `router.replace`; `useTable` replaces per-view paging, request-sequence and page-clamp code; app styles use only CSS variables (`--el-*` or `--cd-*` from `src/styles/tokens.css`), never literal colors (literal values live only in `tokens.css`).
- **Spec values copied verbatim:**
  - Preferences are stored in `localStorage` key **`conduit.ui`**; read/write failures fall back to defaults without an error. Theme: 浅色 / 深色 / 跟随系统, default **跟随系统**. Sidebar style: 浅色 / 深色, default **深色**. Sidebar collapse and the 最新值 view (卡片 / 表格) are preferences too.
  - Dark mode: import `element-plus/theme-chalk/dark/css-vars.css` and toggle the `dark` class on `<html>`; 跟随系统 listens to `prefers-color-scheme` changes.
  - The sidebar auto-collapses when the window is narrower than **820px**.
  - Menu groups: 概览（首页）, 设备接入（产品、设备）, 系统管理（用户、租户 — disabled, labelled 「V0.2-C」）.
  - Document title: `<页面名> · Conduit`; detail pages use the object name.
  - `/` redirects to `/home`; unknown routes show the 404 page with 「回到首页」.
  - `GET /api/v1/overview` response: `productCount`, `propertyCount`, `deviceCount`, `enabledCount`, `disabledCount`, `onlineCount`, `deadLastHour { total, byReason }`, `recentDevices[] { deviceKey, name, productKey, productName, lastReportAt }`, `products[] { productKey, name, deviceCount, onlineCount }`. Per tenant. `recentDevices` = the **5** devices with the latest report time. Dead letters: `conduit:stream:dead` via `XREVRANGE` back to **1 hour** ago, at most **10 000** entries, counted only when `pk` belongs to the tenant's products, grouped by `reason`. When Redis is unavailable the presence-related fields are `null` and the rest is returned; the UI shows 「—」.
  - `ProductView` gains `deviceCount` and `onlineCount` (list and detail).
  - Sparklines: **one** call to `GET /devices/{dk}/history` for at most the first **8** numeric properties over the last **1 h**, refreshed every **60 s**; values still refresh every **5 s**. BOOL/ENUM cards show a text label and no sparkline.
  - Clicking a card opens `?tab=history&props=<identifier>`; `HistoryPanel` reads its initial selection from `props` (comma separated) and drops unknown identifiers.
  - Home: 4 tiles (产品 with 「共定义 N 个属性」; 设备 with 「已启用 N · 已禁用 N」; 在线设备 with an online-rate bar; 最近 1 小时死信 with a per-reason summary, 「无」 when 0), the 「最近上报的设备」 and 「按产品统计」 lists, auto refresh every **30 s**, paused while the page is hidden.
  - `RelativeTime` shows 「3 分钟前」, the full time on hover, and updates every minute. First load shows a table skeleton; paging shows only the table loading mask.

**Facts about the existing code this plan relies on** (verified against the repository):

| Fact | Where |
|---|---|
| Dead-letter records carry the original stream fields (`pk` may be absent, `dk`, `type`, `payload`, `receivedAt`, `attr.*`) plus `reason`, `originalId`, `deliveryCount`, `failedAt` | `RedisStreamMessageBus.recordDead`, `RawMessageCodec.toFields` |
| Dead stream key comes from `MessageBusProperties.deadKey()` (`conduit:stream:dead`; tests use `test:dead`) | `MessageBusProperties`, `application-test.yml` |
| `PresenceStore.getAll(List<String>)` returns only devices with a presence record; `LatestValueStore.lastReportTimes(List<String>)` returns only devices that reported | `presence/PresenceStore`, `telemetry/LatestValueStore` |
| Tables `tenant` and `app_user` are not tenant-filtered; every other table is | `MybatisPlusConfig.TENANT_FREE_TABLES` |
| `JwtAuthFilter` trusts any valid access token and binds its `tenantId`; it does not look the user up | `auth/JwtAuthFilter` |
| The bootstrap creates tenant code `default` with name `Default` | `bootstrap/AdminBootstrap` |
| `echarts/core` registers the built-in `dark` theme | `node_modules/echarts/lib/core/echarts.js` (`registerTheme('dark', darkTheme)`) |
| Simulator and e2e parse JSON with `JsonNode`, so new response fields do not break them | `ConduitApiClient`, `NoDataLossE2EIT` |

---

## File Structure

**conduit-server** (`src/main/java/dev/conduit/server/…`)

| File | Responsibility | Task |
|---|---|---|
| `device/DeviceMapper.java` (modify) | Adds `ProductStatusCount`, `countByProductAndStatus(productIds)`, `selectBrief(productIds)` | 1 |
| `device/DeviceStats.java` (new) | Per-product device / enabled / online counts; `Snapshot`, `ProductCounts`; Redis failure → online `null` | 1 |
| `product/ProductDtos.java`, `product/ProductService.java` (modify) | `ProductView.deviceCount`, `ProductView.onlineCount` | 1 |
| `auth/AuthDtos.java`, `auth/AuthController.java` (modify) | `UserView.tenantName` (login, refresh, `/me`) | 1 |
| `overview/DeadLetterReader.java` (new) | `XREVRANGE` of the dead stream back to a cutoff, capped | 2 |
| `overview/OverviewDtos.java` (new) | `OverviewView`, `DeadSummary`, `RecentDevice`, `ProductStat` | 2 |
| `overview/OverviewService.java` (new) | Tenant overview assembly, dead-letter filter and grouping, Redis fallbacks | 2 |
| `overview/OverviewController.java` (new) | `GET /api/v1/overview` | 2 |

Tests (`src/test/java/dev/conduit/server/…`): `device/DeviceStatsTest`, `product/ProductApiIT` (extended), `auth/AuthIT` (extended) (Task 1); `overview/OverviewServiceTest`, `overview/DeadLetterReaderIT`, `overview/OverviewApiIT` (Task 2).

**conduit-ui** (`src/…`)

| File | Responsibility | Task |
|---|---|---|
| `styles/tokens.css` (new) | `--cd-*` design variables (light + `html.dark`), base page styles, `.cd-panel`/`.cd-pager`/`.cd-muted` | 3 |
| `stores/ui.ts` (new) | Appearance preferences + `localStorage` (`conduit.ui`) | 3 |
| `composables/useTheme.ts` (new) | `resolveDark`, `applyTheme`, `chartTheme`, `installTheme`, `useTheme` | 3 |
| `components/AppearancePanel.vue` (new) | Theme and sidebar-style radio groups | 3 |
| `main.ts` (modify) | Dark CSS vars, tokens, theme install before mount | 3 |
| `utils/historyChart.ts`, `components/HistoryPanel.vue` (modify) | Transparent chart background; charts rebuilt with the ECharts `dark` theme | 3 |
| `router/meta.ts` (new) | `RouteMeta` augmentation, `BreadcrumbItem`, `ROUTE_META` | 4 (home added in 7) |
| `router/index.ts` (modify) | Meta on every route, 404 catch-all (home + `/`→`/home` in 7) | 4, 7 |
| `composables/useBreadcrumb.ts` (new) | `buildBreadcrumb`, `documentTitle`, `useBreadcrumb`, `useCurrentPage` | 4 |
| `composables/useMediaQuery.ts` (new) | Reactive `matchMedia` | 4 |
| `layouts/menu.ts` (new) | Sidebar groups and items | 4 (概览 in 7) |
| `layouts/AppLayout.vue` (rewrite) | Sidebar + top bar | 4 |
| `components/PageHeader.vue`, `views/NotFoundView.vue`, `App.vue` | Page header, 404, document title | 4 |
| `api/types.ts` (modify) | `UserView.tenantName` (4); `ProductView` counts (5); overview types (7) | 4, 5, 7 |
| `composables/useTable.ts` (new) | Server/local paging state | 5 |
| `components/FilterBar.vue`, `OnlineDot.vue`, `RelativeTime.vue`; `utils/relativeTime.ts` (new); `utils/format.ts` (`formatCount`) | List kit | 5 |
| `views/ProductsView.vue`, `DevicesView.vue`, `ProductDetailView.vue` (rewrite) | Migrated lists | 5 |
| `utils/metricCards.ts`, `utils/sparkline.ts` (new); `utils/deviceTabs.ts`, `utils/historyProperties.ts` (modify) | Card models, sparkline option, `props` query, initial selection | 6 |
| `components/Sparkline.vue`, `MetricCard.vue`, `LatestValuesPanel.vue`, `DeviceInfoCard.vue`, `ConnectionDialog.vue` (new) | Device detail parts | 6 |
| `views/DeviceDetailView.vue` (rewrite), `components/HistoryPanel.vue` (`initialProps`) | Device detail | 6 |
| `api/overview.ts`, `utils/overview.ts`, `views/HomeView.vue` (new); `views/LoginView.vue` (rewrite); `utils/redirect.ts` (fallback `/home`) | Home and login | 7 |

**docs:** `README.md`, `CLAUDE.md`, `docs/backlog-v0.2.md`, the spec (status and acceptance record) (Task 8).

**Task order and dependencies:** 1 → 2 (backend); 3 → 4 → 5 → 6 → 7 (frontend; 5 needs the Task 1 JSON shape, 7 needs the Task 2 shape, only as TS types); 8 last. Run them in numeric order.

---
## Task 1: Backend device statistics, product counts and the tenant name

Adds per-product device, enabled and online counts (`DeviceStats`), exposes `deviceCount`/`onlineCount` on `ProductView`, and adds `tenantName` to `UserView` so the top bar can show the tenant.

**Files:**
- Modify: `conduit-server/src/main/java/dev/conduit/server/device/DeviceMapper.java` (full replacement below)
- Create: `conduit-server/src/main/java/dev/conduit/server/device/DeviceStats.java`
- Modify: `conduit-server/src/main/java/dev/conduit/server/product/ProductDtos.java` (full replacement below)
- Modify: `conduit-server/src/main/java/dev/conduit/server/product/ProductService.java` (full replacement below)
- Modify: `conduit-server/src/main/java/dev/conduit/server/auth/AuthDtos.java` (full replacement below)
- Modify: `conduit-server/src/main/java/dev/conduit/server/auth/AuthController.java` (edits below)
- Test: `conduit-server/src/test/java/dev/conduit/server/device/DeviceStatsTest.java` (new)
- Test: `conduit-server/src/test/java/dev/conduit/server/product/ProductApiIT.java` (new test + helper)
- Test: `conduit-server/src/test/java/dev/conduit/server/auth/AuthIT.java` (extended assertions)

**Interfaces:**
- Consumes: `DeviceMapper` (MyBatis-Plus `BaseMapper<Device>`), `Device` (`getId/getProductId/getDeviceKey/getName/getStatus`, `Device.ENABLED`), `PresenceStore.getAll(List<String>) → Map<String, Presence>`, `Presence.online()`, `ProductPropertyMapper.countByProduct/countByProducts`, `TenantMapper.selectById`, `AuthPrincipal(userId, tenantId, username, role)`, `IntegrationTestBase` helpers (`createDevice`, `defaultTenantId`, `unique`, `secretHasher`, `jdbc`).
- Produces (used by Task 2 and the frontend):
  - `DeviceMapper.ProductStatusCount(long productId, String status, int count)`; `List<ProductStatusCount> countByProductAndStatus(Collection<Long> productIds)`; `List<Device> selectBrief(Collection<Long> productIds)` — `null` means all products of the current tenant, an empty collection returns an empty list.
  - `@Component DeviceStats(DeviceMapper, PresenceStore)`: `Snapshot snapshot(Collection<Long> productIds)` (`null` = whole tenant).
  - `DeviceStats.ProductCounts(int deviceCount, int enabledCount, Integer onlineCount)`.
  - `DeviceStats.Snapshot(List<Device> devices, Map<Long, ProductCounts> byProduct, Set<String> onlineKeys)` with `presenceAvailable()`, `of(long productId)`, `deviceCount()`, `enabledCount()`, `onlineCount()` (`Integer`, `null` when Redis was unavailable).
  - JSON `ProductView`: `productKey, name, description, codec, propertyCount, deviceCount, onlineCount (number|null), createdAt`.
  - JSON `UserView`: `username, role, tenantName (string|null)` in the login/refresh `TokenResponse.user` and in `GET /api/v1/auth/me`.

**Decisions recorded here:**
- `DeviceStats` lives in the `device` package and is shared by `ProductService` (list/detail counts) and `OverviewService` (Task 2), so "online" is computed one way. Device and enabled counts come from one PostgreSQL `GROUP BY product_id, status` (spec §5.1); online counts need the device keys, so a slim `selectBrief` reads `id, product_id, device_key, name, status` and `PresenceStore.getAll` is called once.
- `onlineCount` is `Integer` and `null` when Redis presence cannot be read (any `DataAccessException`), on `ProductView` as on the overview. Device counts are still returned. The failure is logged at WARN.
- A newly created product returns `deviceCount 0, onlineCount 0` without touching Redis.
- The spec's top bar shows the tenant name, but `UserView` had none. This plan adds `tenantName` to `UserView` (read from `tenant`, which is not tenant-filtered) rather than a new endpoint. A missing tenant row gives `null`.

- [ ] **Step 1: Write the failing `DeviceStats` unit test**

Create `conduit-server/src/test/java/dev/conduit/server/device/DeviceStatsTest.java`:

```java
package dev.conduit.server.device;

import dev.conduit.server.device.DeviceMapper.ProductStatusCount;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.device.DeviceStats.Snapshot;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DeviceStats 单元测试：按产品统计设备数、启用数与在线数；Redis 不可用时在线数为 null；空产品集合不读 Redis。 */
class DeviceStatsTest {

    private final DeviceMapper devices = mock(DeviceMapper.class);
    private final PresenceStore presence = mock(PresenceStore.class);
    private final DeviceStats stats = new DeviceStats(devices, presence);

    private static Device device(long productId, String deviceKey) {
        Device d = new Device();
        d.setProductId(productId);
        d.setDeviceKey(deviceKey);
        d.setName("设备 " + deviceKey);
        d.setStatus(Device.ENABLED);
        return d;
    }

    private void givenTwoProducts() {
        when(devices.countByProductAndStatus(isNull())).thenReturn(List.of(
                new ProductStatusCount(1, "ENABLED", 2),
                new ProductStatusCount(1, "DISABLED", 1),
                new ProductStatusCount(2, "ENABLED", 1)));
        when(devices.selectBrief(isNull())).thenReturn(List.of(
                device(1, "a"), device(1, "b"), device(1, "c"), device(2, "d")));
    }

    @Test
    void countsDevicesEnabledAndOnlinePerProduct() {
        givenTwoProducts();
        when(presence.getAll(List.of("a", "b", "c", "d"))).thenReturn(Map.of(
                "a", new Presence(true, 1, ""),
                "b", new Presence(false, 1, ""),
                "d", new Presence(true, 1, "")));

        Snapshot s = stats.snapshot(null);

        assertThat(s.of(1)).isEqualTo(new ProductCounts(3, 2, 1));
        assertThat(s.of(2)).isEqualTo(new ProductCounts(1, 1, 1));
        assertThat(s.of(99)).isEqualTo(new ProductCounts(0, 0, 0));
        assertThat(s.deviceCount()).isEqualTo(4);
        assertThat(s.enabledCount()).isEqualTo(3);
        assertThat(s.onlineCount()).isEqualTo(2);
        assertThat(s.presenceAvailable()).isTrue();
        assertThat(s.devices()).hasSize(4);
    }

    @Test
    void redisUnavailableLeavesOnlineCountsNullButKeepsDeviceCounts() {
        givenTwoProducts();
        when(presence.getAll(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        Snapshot s = stats.snapshot(null);

        assertThat(s.presenceAvailable()).isFalse();
        assertThat(s.of(1)).isEqualTo(new ProductCounts(3, 2, null));
        assertThat(s.of(99)).isEqualTo(new ProductCounts(0, 0, null));
        assertThat(s.onlineCount()).isNull();
        assertThat(s.deviceCount()).isEqualTo(4);
    }

    @Test
    void productsWithoutDevicesDoNotTouchRedis() {
        when(devices.countByProductAndStatus(List.of(7L))).thenReturn(List.of());
        when(devices.selectBrief(List.of(7L))).thenReturn(List.of());

        Snapshot s = stats.snapshot(List.of(7L));

        assertThat(s.of(7)).isEqualTo(new ProductCounts(0, 0, 0));
        assertThat(s.onlineCount()).isZero();
        verify(presence, never()).getAll(anyList());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=DeviceStatsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol: class DeviceStats` / `ProductStatusCount`.

- [ ] **Step 3: Add the mapper queries**

Replace `conduit-server/src/main/java/dev/conduit/server/device/DeviceMapper.java` with:

```java
package dev.conduit.server.device;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 设备的 MyBatis-Plus Mapper，常规查询受租户拦截器过滤；findIdentity 是显式绕过租户过滤的内部专用查询。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /**
     * 按产品与状态分组的设备数（一行）。
     *
     * @param productId 产品 id
     * @param status    ENABLED 或 DISABLED
     * @param count     设备数
     */
    record ProductStatusCount(long productId, String status, int count) {
    }

    /**
     * 跨租户按 deviceKey 查设备身份（联查产品取 productKey）。供 MQTT 鉴权与流水线使用，这两处没有租户上下文，禁止用于面向用户的接口。
     * 设备不存在返回 null。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select d.device_key, d.tenant_id, d.status, d.secret_hash, d.secret_salt, p.product_key
            from device d join product p on p.id = d.product_id
            where d.device_key = #{deviceKey}
            """)
    DeviceIdentity findIdentity(@Param("deviceKey") String deviceKey);

    /**
     * 当前租户的设备按产品、状态分组计数（一次 GROUP BY 查询）。
     *
     * @param productIds 限定的产品 id；null 表示当前租户全部产品，空集合直接返回空列表（不查库）
     */
    default List<ProductStatusCount> countByProductAndStatus(Collection<Long> productIds) {
        if (productIds != null && productIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<Device> query = new QueryWrapper<Device>().select("product_id as pid", "status", "count(*) as cnt");
        if (productIds != null) {
            query.in("product_id", productIds);
        }
        query.groupBy("product_id", "status");
        return selectMaps(query).stream()
                .map(r -> new ProductStatusCount(((Number) r.get("pid")).longValue(), (String) r.get("status"),
                        ((Number) r.get("cnt")).intValue()))
                .toList();
    }

    /**
     * 当前租户设备的精简列表，只取 id、product_id、device_key、name、status，供统计在线数与最近上报使用。
     *
     * @param productIds 限定的产品 id；null 表示当前租户全部产品，空集合直接返回空列表（不查库）
     */
    default List<Device> selectBrief(Collection<Long> productIds) {
        if (productIds != null && productIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>()
                .select(Device::getId, Device::getProductId, Device::getDeviceKey, Device::getName, Device::getStatus);
        if (productIds != null) {
            query.in(Device::getProductId, productIds);
        }
        return selectList(query);
    }
}
```

- [ ] **Step 4: Implement `DeviceStats`**

Create `conduit-server/src/main/java/dev/conduit/server/device/DeviceStats.java`:

```java
package dev.conduit.server.device;

import dev.conduit.server.device.DeviceMapper.ProductStatusCount;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按产品统计设备数、启用数与在线数：设备数与启用数来自 PostgreSQL 分组计数（租户拦截器过滤），
 * 在线数来自 Redis 在线状态（一次管道批量读取）。Redis 不可用时在线数为 null，其余照常。
 * 供产品列表/详情的「设备（在线/总数）」与首页总览使用。本期把统计范围内的设备全部读出，设备到万级时改为增量计数（见 backlog）。
 */
@Component
public class DeviceStats {

    private static final Logger log = LoggerFactory.getLogger(DeviceStats.class);

    private final DeviceMapper devices;
    private final PresenceStore presence;

    public DeviceStats(DeviceMapper devices, PresenceStore presence) {
        this.devices = devices;
        this.presence = presence;
    }

    /**
     * 一个产品的设备计数。
     *
     * @param deviceCount  设备总数
     * @param enabledCount 启用的设备数
     * @param onlineCount  在线设备数；Redis 不可用时为 null
     */
    public record ProductCounts(int deviceCount, int enabledCount, Integer onlineCount) {

        /** 没有设备的产品：在线数随 Redis 是否可用为 0 或 null。 */
        static ProductCounts empty(boolean presenceAvailable) {
            return new ProductCounts(0, 0, presenceAvailable ? 0 : null);
        }
    }

    /**
     * 一次统计的结果。
     *
     * @param devices    统计范围内的设备精简信息（id、productId、deviceKey、name、status）
     * @param byProduct  有设备的产品的计数，键为产品 id
     * @param onlineKeys 在线设备的 deviceKey；Redis 不可用时为 null
     */
    public record Snapshot(List<Device> devices, Map<Long, ProductCounts> byProduct, Set<String> onlineKeys) {

        /** 是否成功读到了在线状态。 */
        public boolean presenceAvailable() {
            return onlineKeys != null;
        }

        /** 指定产品的计数；该产品没有设备时为 0（在线数在 Redis 不可用时为 null）。 */
        public ProductCounts of(long productId) {
            return byProduct.getOrDefault(productId, ProductCounts.empty(presenceAvailable()));
        }

        /** 统计范围内的设备总数。 */
        public int deviceCount() {
            return byProduct.values().stream().mapToInt(ProductCounts::deviceCount).sum();
        }

        /** 统计范围内启用的设备数。 */
        public int enabledCount() {
            return byProduct.values().stream().mapToInt(ProductCounts::enabledCount).sum();
        }

        /** 统计范围内的在线设备数；Redis 不可用时为 null。 */
        public Integer onlineCount() {
            return onlineKeys == null ? null : onlineKeys.size();
        }
    }

    /**
     * 统计设备：PostgreSQL 分组计数得到每个产品的设备数与启用数，再批量读 Redis 在线状态得到在线数。
     *
     * @param productIds 限定的产品 id；null 表示当前租户全部产品
     * @return 统计结果；Redis 不可用时 onlineKeys 为 null、各产品在线数为 null
     */
    public Snapshot snapshot(Collection<Long> productIds) {
        List<ProductStatusCount> counts = devices.countByProductAndStatus(productIds);
        List<Device> brief = devices.selectBrief(productIds);
        Set<String> online = onlineKeys(brief);

        Map<Long, Integer> onlineByProduct = new HashMap<>();
        if (online != null) {
            for (Device d : brief) {
                if (online.contains(d.getDeviceKey())) {
                    onlineByProduct.merge(d.getProductId(), 1, Integer::sum);
                }
            }
        }
        Map<Long, int[]> totals = new HashMap<>();
        for (ProductStatusCount c : counts) {
            int[] t = totals.computeIfAbsent(c.productId(), id -> new int[2]);
            t[0] += c.count();
            if (Device.ENABLED.equals(c.status())) {
                t[1] += c.count();
            }
        }
        Map<Long, ProductCounts> byProduct = new HashMap<>();
        totals.forEach((productId, t) -> byProduct.put(productId, new ProductCounts(t[0], t[1],
                online == null ? null : onlineByProduct.getOrDefault(productId, 0))));
        return new Snapshot(brief, byProduct, online);
    }

    /**
     * 读取在线设备集合。没有设备时直接返回空集合（不访问 Redis）；Redis 不可用时记录告警并返回 null。
     */
    private Set<String> onlineKeys(List<Device> brief) {
        if (brief.isEmpty()) {
            return Set.of();
        }
        try {
            Map<String, Presence> states = presence.getAll(brief.stream().map(Device::getDeviceKey).toList());
            Set<String> online = new HashSet<>();
            states.forEach((deviceKey, p) -> {
                if (p.online()) {
                    online.add(deviceKey);
                }
            });
            return online;
        } catch (DataAccessException e) {
            log.warn("Presence store unavailable, online counts omitted", e);
            return null;
        }
    }
}
```

- [ ] **Step 5: Run the unit test to verify it passes**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=DeviceStatsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (3 tests).

- [ ] **Step 6: Write the failing product-count and tenant-name integration tests**

In `conduit-server/src/test/java/dev/conduit/server/product/ProductApiIT.java`:

1. Add the class header comment directly above `class ProductApiIT extends IntegrationTestBase {`:

```java
/** 产品接口集成测试：增删改查、分页、校验、租户隔离、解码器、属性数，以及设备数与在线数。 */
```

2. Add these imports:

```java
import dev.conduit.server.presence.PresenceStore;
import org.springframework.beans.factory.annotation.Autowired;
```

3. Add this field next to `String auth;`:

```java
    @Autowired
    PresenceStore presence;
```

4. Add this test and helper at the end of the class (before the final `}`):

```java
    @Test
    void deviceAndOnlineCountsAreReportedInCreateGetAndList() throws Exception {
        String pk = createProduct("设备计数产品");
        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.deviceCount").value(0))
                .andExpect(jsonPath("$.onlineCount").value(0));

        long productId = jdbc.queryForObject("select id from product where product_key = ?", Long.class, pk);
        String online = insertDevice(productId, "ENABLED");
        String offline = insertDevice(productId, "ENABLED");
        insertDevice(productId, "DISABLED");
        long now = System.currentTimeMillis();
        presence.apply(online, true, now, "");
        presence.apply(offline, false, now, "");

        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCount").value(3))
                .andExpect(jsonPath("$.onlineCount").value(1));
        mvc.perform(get("/api/v1/products").param("size", "100").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items[?(@.productKey == '" + pk + "')].deviceCount",
                        org.hamcrest.Matchers.contains(3)))
                .andExpect(jsonPath("$.items[?(@.productKey == '" + pk + "')].onlineCount",
                        org.hamcrest.Matchers.contains(1)));
    }

    /** 直接写库在默认租户的指定产品下建一台设备，返回 deviceKey。 */
    private String insertDevice(long productId, String status) {
        String deviceKey = unique("dk");
        String salt = secretHasher.newSalt();
        jdbc.update("insert into device (tenant_id, product_id, device_key, name, secret_hash, secret_salt, status) "
                        + "values (?, ?, ?, '计数测试设备', ?, ?, ?)",
                defaultTenantId(), productId, deviceKey, secretHasher.hash("secret-" + deviceKey, salt), salt, status);
        return deviceKey;
    }
```

In `conduit-server/src/test/java/dev/conduit/server/auth/AuthIT.java`:

1. Add directly above `class AuthIT extends IntegrationTestBase {`:

```java
/** 认证接口集成测试：登录、刷新、登出、/me，以及当前用户视图中的租户名称。 */
```

2. In `loginReturnsAccessTokenAndRefreshCookie`, replace

```java
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
```

with

```java
        assertThat(result.getResponse().getContentAsString()).contains("\"tenantName\":\"Default\"");
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.tenantName").value("Default"));
```

- [ ] **Step 7: Run them to verify they fail**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test='ProductApiIT,AuthIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: FAIL — `ProductApiIT.deviceAndOnlineCountsAreReportedInCreateGetAndList` (`No value at JSON path "$.deviceCount"`) and `AuthIT.loginReturnsAccessTokenAndRefreshCookie` (no `tenantName`).

- [ ] **Step 8: Add the counts to `ProductView`**

Replace `conduit-server/src/main/java/dev/conduit/server/product/ProductDtos.java` with:

```java
package dev.conduit.server.product;

import dev.conduit.server.device.DeviceStats.ProductCounts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 产品接口的请求与响应对象集合。
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /** codec 可选：新建时缺省为 standard-json，修改时缺省保持不变。 */
    public record SaveProductRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description,
                                     @Size(max = 32) String codec) {
    }

    /**
     * 产品响应视图。
     *
     * @param propertyCount 物模型属性数
     * @param deviceCount   产品下的设备数
     * @param onlineCount   产品下的在线设备数；Redis 不可用时为 null
     */
    public record ProductView(String productKey, String name, String description, String codec, int propertyCount,
                              int deviceCount, Integer onlineCount, OffsetDateTime createdAt) {

        /** 由实体、属性数与设备计数组装视图。 */
        static ProductView of(Product p, int propertyCount, ProductCounts counts) {
            return new ProductView(p.getProductKey(), p.getName(), p.getDescription(), p.getCodec(), propertyCount,
                    counts.deviceCount(), counts.onlineCount(), p.getCreatedAt());
        }
    }
}
```

Replace `conduit-server/src/main/java/dev/conduit/server/product/ProductService.java` with:

```java
package dev.conduit.server.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceMapper;
import dev.conduit.server.device.DeviceStats;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.StandardJsonDecoder;
import dev.conduit.server.product.ProductDtos.ProductView;
import dev.conduit.server.product.ProductDtos.SaveProductRequest;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.thingmodel.ProductPropertyMapper;
import dev.conduit.server.thingmodel.ThingModelCache;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 产品的增删改查。产品属于当前租户（租户过滤由拦截器完成）；响应带属性数与设备数（在线/总数，见 DeviceStats）；
 * 物模型缓存在产品变更、删除时同步清除。
 */
@Service
public class ProductService {

    private static final ProductCounts NO_DEVICES = new ProductCounts(0, 0, 0);

    private final ProductMapper products;
    private final DeviceMapper devices;
    private final ProductPropertyMapper properties;
    private final DeviceStats stats;
    private final DecoderRegistry decoders;
    private final ThingModelCache thingModels;
    private final KeyGenerator keys;
    private final Clock clock;

    public ProductService(ProductMapper products, DeviceMapper devices, ProductPropertyMapper properties,
                          DeviceStats stats, DecoderRegistry decoders, ThingModelCache thingModels, KeyGenerator keys,
                          Clock clock) {
        this.products = products;
        this.devices = devices;
        this.properties = properties;
        this.stats = stats;
        this.decoders = decoders;
        this.thingModels = thingModels;
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * 新建产品，productKey 由系统生成。codec 缺省为 standard-json；指定的解码器不存在抛 400。新产品没有设备，计数直接为 0。
     */
    public ProductView create(SaveProductRequest request) {
        Product p = new Product();
        p.setProductKey(keys.productKey());
        p.setName(request.name());
        p.setDescription(request.description());
        p.setCodec(requireCodec(request.codec() == null ? StandardJsonDecoder.NAME : request.codec()));
        products.insert(p);
        return ProductView.of(products.selectById(p.getId()), 0, NO_DEVICES);
    }

    /**
     * 分页列出当前租户的产品，按 id 倒序（新的在前）。属性数与设备计数都对本页产品各做一次批量查询，避免逐个产品查询。page 从 1 开始。
     */
    public PageView<ProductView> list(int page, int size) {
        Page<Product> result = products.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Product>().orderByDesc(Product::getId));
        List<Long> ids = result.getRecords().stream().map(Product::getId).toList();
        Map<Long, Integer> counts = properties.countByProducts(ids);
        DeviceStats.Snapshot snapshot = stats.snapshot(ids);
        return new PageView<>(result.getRecords().stream()
                .map(p -> ProductView.of(p, counts.getOrDefault(p.getId(), 0), snapshot.of(p.getId()))).toList(),
                result.getTotal(), page, size);
    }

    /**
     * 查询单个产品（含属性数与设备计数），不存在抛 404。
     */
    public ProductView get(String productKey) {
        return view(require(productKey));
    }

    /**
     * 修改名称、描述（null 表示清空）；codec 为 null 时保持不变，不存在的解码器抛 400。修改后清除物模型缓存（缓存中含 codec）。
     */
    public ProductView update(String productKey, SaveProductRequest request) {
        Product p = require(productKey);
        p.setName(request.name());
        p.setDescription(request.description());
        if (request.codec() != null) {
            p.setCodec(requireCodec(request.codec()));
        }
        p.setUpdatedAt(OffsetDateTime.now(clock));
        products.updateById(p);
        thingModels.evict(productKey);
        return view(p);
    }

    /**
     * 删除产品。产品下仍有设备抛 409；成功后清除物模型缓存。
     */
    public void delete(String productKey) {
        Product p = require(productKey);
        boolean hasDevices = devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getProductId, p.getId()));
        if (hasDevices) {
            throw ApiException.conflict("产品下仍有设备，不能删除");
        }
        products.deleteById(p.getId());
        thingModels.evict(productKey);
    }

    /**
     * 按 productKey 取当前租户的产品实体，不存在（含属于其他租户）抛 404。供其他服务复用。
     */
    public Product require(String productKey) {
        Product p = products.selectOne(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (p == null) {
            throw ApiException.notFound("产品不存在: " + productKey);
        }
        return p;
    }

    /** 单个产品的响应视图：属性数与设备计数各查一次。 */
    private ProductView view(Product p) {
        return ProductView.of(p, (int) properties.countByProduct(p.getId()),
                stats.snapshot(List.of(p.getId())).of(p.getId()));
    }

    /**
     * 校验解码器已注册，否则抛 400；返回原值。
     */
    private String requireCodec(String codec) {
        if (!decoders.exists(codec)) {
            throw ApiException.badRequest("解码器不存在: " + codec);
        }
        return codec;
    }
}
```

- [ ] **Step 9: Add the tenant name to `UserView`**

Replace `conduit-server/src/main/java/dev/conduit/server/auth/AuthDtos.java` with:

```java
package dev.conduit.server.auth;

import jakarta.validation.constraints.NotBlank;

/** 认证接口（{@link AuthController}）的请求/响应 DTO 集合。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** 登录请求，用户名与密码均不可为空。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * 返回给 UI 的当前用户信息（不含任何敏感字段）。
     *
     * @param tenantName 所属租户名称，顶栏显示；租户不存在时为 null
     */
    public record UserView(String username, String role, String tenantName) {
    }

    /**
     * 登录/刷新响应。
     *
     * @param accessToken access token
     * @param expiresIn   access token 有效期，单位秒
     * @param user        当前用户
     */
    public record TokenResponse(String accessToken, long expiresIn, UserView user) {
    }
}
```

In `conduit-server/src/main/java/dev/conduit/server/auth/AuthController.java`:

1. Add imports:

```java
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
```

2. Replace the fields-and-constructor block

```java
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties props;

    public AuthController(AppUserMapper users, PasswordEncoder encoder, JwtService jwt, JwtProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }
```

with

```java
    private final AppUserMapper users;
    private final TenantMapper tenants;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties props;

    public AuthController(AppUserMapper users, TenantMapper tenants, PasswordEncoder encoder, JwtService jwt,
                          JwtProperties props) {
        this.users = users;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }
```

3. Replace the `me` method

```java
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthPrincipal principal) {
        return new UserView(principal.username(), principal.role());
    }
```

with

```java
    /** 当前登录用户（含所属租户名称）。 */
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthPrincipal principal) {
        return userView(principal);
    }
```

4. In `tokens(...)`, replace

```java
                .body(new TokenResponse(access, props.accessTtl().toSeconds(),
                        new UserView(principal.username(), principal.role())));
```

with

```java
                .body(new TokenResponse(access, props.accessTtl().toSeconds(), userView(principal)));
```

5. Add this method directly above `private static AuthPrincipal principalOf(AppUser user) {`:

```java
    /**
     * 组装给 UI 的用户视图：租户名称按 tenantId 查 tenant 表（该表不做租户行过滤）；租户不存在时名称为 null。
     */
    private UserView userView(AuthPrincipal principal) {
        Tenant tenant = tenants.selectById(principal.tenantId());
        return new UserView(principal.username(), principal.role(), tenant == null ? null : tenant.getName());
    }
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=DeviceStatsTest -Dsurefire.failIfNoSpecifiedTests=false`
Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test='ProductApiIT,AuthIT,DeviceApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: all PASS. `DeviceApiIT` is included because `DeviceService` depends on `ProductService`, whose constructor changed.

- [ ] **Step 11: Commit**

```bash
git add conduit-server/src/main/java/dev/conduit/server/device/DeviceMapper.java \
  conduit-server/src/main/java/dev/conduit/server/device/DeviceStats.java \
  conduit-server/src/main/java/dev/conduit/server/product/ProductDtos.java \
  conduit-server/src/main/java/dev/conduit/server/product/ProductService.java \
  conduit-server/src/main/java/dev/conduit/server/auth/AuthDtos.java \
  conduit-server/src/main/java/dev/conduit/server/auth/AuthController.java \
  conduit-server/src/test/java/dev/conduit/server/device/DeviceStatsTest.java \
  conduit-server/src/test/java/dev/conduit/server/product/ProductApiIT.java \
  conduit-server/src/test/java/dev/conduit/server/auth/AuthIT.java
git commit -m "feat(server): product device/online counts and tenant name in user view"
```

---
## Task 2: Backend `GET /api/v1/overview`

Adds the read-only, per-tenant overview for the home page (spec §5.1).

**Files:**
- Create: `conduit-server/src/main/java/dev/conduit/server/overview/DeadLetterReader.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/overview/OverviewDtos.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/overview/OverviewService.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/overview/OverviewController.java`
- Test: `conduit-server/src/test/java/dev/conduit/server/overview/OverviewServiceTest.java` (new)
- Test: `conduit-server/src/test/java/dev/conduit/server/overview/DeadLetterReaderIT.java` (new)
- Test: `conduit-server/src/test/java/dev/conduit/server/overview/OverviewApiIT.java` (new)

**Interfaces:**
- Consumes: Task 1 `DeviceStats.snapshot(null)`, `DeviceStats.Snapshot` (`devices()`, `of(id)`, `deviceCount()`, `enabledCount()`, `onlineCount()`), `DeviceStats.ProductCounts`; `ProductMapper.selectList`, `ProductPropertyMapper.selectCount`, `LatestValueStore.lastReportTimes(List<String>)`, `MessageBusProperties.deadKey()`, the `Clock` bean, `JwtService.issue(AuthPrincipal, TokenType)`, `PresenceStore.apply`, `LatestValueStore.update`, `IntegrationTestBase` (`createTenant`, `insertProperty`, `unique`, `secretHasher`, `bearer`).
- Produces:
  - `@Component DeadLetterReader(StringRedisTemplate, MessageBusProperties)`: `List<Map<String,String>> since(long sinceMillis, int limit)` — newest first, entries whose stream ID time is `>= sinceMillis`, at most `limit`; empty list when the stream does not exist; throws `DataAccessException` when Redis is down.
  - `@Service OverviewService`: `OverviewView overview()`; constants `DEAD_WINDOW = 1h`, `DEAD_SCAN_LIMIT = 10_000`, `RECENT_LIMIT = 5`; `static DeadSummary summarize(List<Map<String,String>> records, Set<String> productKeys)`.
  - `OverviewDtos.OverviewView(int productCount, int propertyCount, int deviceCount, int enabledCount, int disabledCount, Integer onlineCount, DeadSummary deadLastHour, List<RecentDevice> recentDevices, List<ProductStat> products)`, `DeadSummary(int total, Map<String,Integer> byReason)`, `RecentDevice(String deviceKey, String name, String productKey, String productName, long lastReportAt)`, `ProductStat(String productKey, String name, int deviceCount, Integer onlineCount)`.
  - `GET /api/v1/overview` → `OverviewView` JSON (authenticated; tenant from the token).

**Decisions recorded here:**
- "Presence-related fields" when Redis is unavailable: `onlineCount` and every `products[].onlineCount` are `null` (from `DeviceStats`), `recentDevices` is `[]` (its times come from Redis), and `deadLastHour` is `null` (the dead stream is Redis too). Each Redis read is guarded separately, so one failing read does not hide the others. PostgreSQL counts are always returned.
- The dead-letter window is by stream ID: `XREVRANGE <deadKey> + <now-1h>-0 COUNT 10000`. The stream ID's millisecond part is the Redis server time of `XADD`, which is the `failedAt` moment. Entries without `pk` (the transport omits it when the topic has none) cannot be attributed to a tenant and are skipped. A missing `reason` counts as `UNKNOWN`. `byReason` is sorted by reason name (`TreeMap`) for a stable JSON order.
- Because the scan stops at 10 000 entries, `deadLastHour.total` saturates at 10 000 per platform-wide hour. This is recorded in the backlog (Task 8) together with the "all devices are read" cost.
- `products` lists every product of the tenant, sorted by `deviceCount` descending; ties keep the product id descending order (newest first). `recentDevices` holds at most 5 devices that have reported, newest report first.
- `propertyCount` is the number of thing-model properties over all products of the tenant (`SELECT COUNT(*) FROM product_property` with the tenant filter).
- `OverviewApiIT` issues tokens for fresh tenants with `JwtService.issue` (the filter does not look up the user), so no test users are created.

- [ ] **Step 1: Write the failing service unit test**

Create `conduit-server/src/test/java/dev/conduit/server/overview/OverviewServiceTest.java`:

```java
package dev.conduit.server.overview;

import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceStats;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.overview.OverviewDtos.OverviewView;
import dev.conduit.server.overview.OverviewDtos.ProductStat;
import dev.conduit.server.overview.OverviewDtos.RecentDevice;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.thingmodel.ProductPropertyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OverviewService 单元测试：计数与两个列表、死信按租户产品过滤并按原因分组、Redis 不可用时相关字段为 null。 */
class OverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");

    private final ProductMapper products = mock(ProductMapper.class);
    private final ProductPropertyMapper properties = mock(ProductPropertyMapper.class);
    private final DeviceStats stats = mock(DeviceStats.class);
    private final LatestValueStore latest = mock(LatestValueStore.class);
    private final DeadLetterReader deadLetters = mock(DeadLetterReader.class);
    private final OverviewService service = new OverviewService(products, properties, stats, latest, deadLetters,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<Device> devices = List.of(
            device(1, "dk1", "1号楼配电室 电表01", "ENABLED"),
            device(1, "dk2", "1号楼配电室 电表02", "ENABLED"),
            device(2, "dk3", "1号楼 水表01", "DISABLED"));

    private static Product product(long id, String key, String name) {
        Product p = new Product();
        p.setId(id);
        p.setProductKey(key);
        p.setName(name);
        return p;
    }

    private static Device device(long productId, String key, String name, String status) {
        Device d = new Device();
        d.setProductId(productId);
        d.setDeviceKey(key);
        d.setName(name);
        d.setStatus(status);
        return d;
    }

    private static Map<String, String> dead(String productKey, String reason) {
        Map<String, String> record = new HashMap<>();
        if (productKey != null) {
            record.put("pk", productKey);
        }
        record.put("dk", "dk-x");
        record.put("reason", reason);
        return record;
    }

    @BeforeEach
    void setUp() {
        // id 倒序：水表（id 2）在前
        when(products.selectList(any())).thenReturn(List.of(product(2, "pk2", "水表（演示）"), product(1, "pk1", "三相电表（演示）")));
        when(properties.selectCount(any())).thenReturn(15L);
        when(stats.snapshot(isNull())).thenReturn(new DeviceStats.Snapshot(devices,
                Map.of(1L, new ProductCounts(2, 2, 1), 2L, new ProductCounts(1, 0, 0)), Set.of("dk1")));
        when(latest.lastReportTimes(anyList())).thenReturn(Map.of("dk1", 3000L, "dk3", 5000L));
        when(deadLetters.since(anyLong(), anyInt())).thenReturn(List.of(
                dead("pk1", "FIELD_REJECTED"), dead("pk1", "FIELD_REJECTED"), dead("pk2", "MALFORMED_JSON"),
                dead("pk-other-tenant", "PROCESSING_ERROR"), dead(null, "UNKNOWN_DEVICE")));
    }

    @Test
    void countsDevicesAndListsRecentDevicesAndProducts() {
        OverviewView v = service.overview();

        assertThat(v.productCount()).isEqualTo(2);
        assertThat(v.propertyCount()).isEqualTo(15);
        assertThat(v.deviceCount()).isEqualTo(3);
        assertThat(v.enabledCount()).isEqualTo(2);
        assertThat(v.disabledCount()).isEqualTo(1);
        assertThat(v.onlineCount()).isEqualTo(1);
        assertThat(v.recentDevices()).containsExactly(
                new RecentDevice("dk3", "1号楼 水表01", "pk2", "水表（演示）", 5000L),
                new RecentDevice("dk1", "1号楼配电室 电表01", "pk1", "三相电表（演示）", 3000L));
        assertThat(v.products()).containsExactly(
                new ProductStat("pk1", "三相电表（演示）", 2, 1),
                new ProductStat("pk2", "水表（演示）", 1, 0));
    }

    @Test
    void deadLettersAreFilteredByTenantProductsAndGroupedByReason() {
        OverviewView v = service.overview();

        assertThat(v.deadLastHour().total()).isEqualTo(3);
        assertThat(v.deadLastHour().byReason()).containsExactly(
                Map.entry("FIELD_REJECTED", 2), Map.entry("MALFORMED_JSON", 1));
        verify(deadLetters).since(NOW.toEpochMilli() - 3_600_000L, 10_000);
    }

    @Test
    void keepsAtMostFiveRecentDevicesNewestFirst() {
        List<Device> many = List.of(
                device(1, "a", "A", "ENABLED"), device(1, "b", "B", "ENABLED"), device(1, "c", "C", "ENABLED"),
                device(1, "d", "D", "ENABLED"), device(1, "e", "E", "ENABLED"), device(1, "f", "F", "ENABLED"));
        when(stats.snapshot(isNull())).thenReturn(new DeviceStats.Snapshot(many,
                Map.of(1L, new ProductCounts(6, 6, 0)), Set.of()));
        when(latest.lastReportTimes(anyList())).thenReturn(Map.of("a", 1L, "b", 6L, "c", 3L, "d", 5L, "e", 2L, "f", 4L));

        assertThat(service.overview().recentDevices()).extracting(RecentDevice::deviceKey)
                .containsExactly("b", "d", "f", "c", "e");
    }

    @Test
    void redisUnavailableNullsPresenceFieldsButKeepsTheRest() {
        when(stats.snapshot(isNull())).thenReturn(new DeviceStats.Snapshot(devices,
                Map.of(1L, new ProductCounts(2, 2, null), 2L, new ProductCounts(1, 0, null)), null));
        when(latest.lastReportTimes(anyList())).thenThrow(new RedisConnectionFailureException("down"));
        when(deadLetters.since(anyLong(), anyInt())).thenThrow(new RedisConnectionFailureException("down"));

        OverviewView v = service.overview();

        assertThat(v.onlineCount()).isNull();
        assertThat(v.products()).extracting(ProductStat::onlineCount).containsOnlyNulls();
        assertThat(v.recentDevices()).isEmpty();
        assertThat(v.deadLastHour()).isNull();
        assertThat(v.deviceCount()).isEqualTo(3);
        assertThat(v.enabledCount()).isEqualTo(2);
        assertThat(v.productCount()).isEqualTo(2);
        assertThat(v.propertyCount()).isEqualTo(15);
    }

    @Test
    void noDeadLettersGiveAnEmptySummary() {
        when(deadLetters.since(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.overview().deadLastHour()).isEqualTo(new OverviewDtos.DeadSummary(0, Map.of()));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=OverviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `package dev.conduit.server.overview does not exist` / `cannot find symbol: class OverviewService`.

- [ ] **Step 3: Implement the DTOs**

Create `conduit-server/src/main/java/dev/conduit/server/overview/OverviewDtos.java`:

```java
package dev.conduit.server.overview;

import java.util.List;
import java.util.Map;

/**
 * 首页总览接口（GET /api/v1/overview）的响应对象集合。在线相关字段在 Redis 不可用时为 null。
 */
public final class OverviewDtos {

    private OverviewDtos() {
    }

    /**
     * 当前租户的总览。
     *
     * @param productCount  产品数
     * @param propertyCount 所有产品的物模型属性总数
     * @param deviceCount   设备数
     * @param enabledCount  启用的设备数
     * @param disabledCount 禁用的设备数
     * @param onlineCount   在线设备数；Redis 不可用时为 null
     * @param deadLastHour  最近 1 小时的死信统计；Redis 不可用时为 null
     * @param recentDevices 最后上报时间最近的设备（最多 5 台，新的在前）；Redis 不可用时为空列表
     * @param products      每个产品的设备数与在线数，按设备数降序
     */
    public record OverviewView(int productCount, int propertyCount, int deviceCount, int enabledCount,
                               int disabledCount, Integer onlineCount, DeadSummary deadLastHour,
                               List<RecentDevice> recentDevices, List<ProductStat> products) {
    }

    /**
     * 死信统计。
     *
     * @param total    条数
     * @param byReason 按死信原因（DeadReason 名称）分组的条数，按原因名称排序
     */
    public record DeadSummary(int total, Map<String, Integer> byReason) {
    }

    /**
     * 最近上报的设备。
     *
     * @param lastReportAt 最后上报时间（毫秒）
     */
    public record RecentDevice(String deviceKey, String name, String productKey, String productName, long lastReportAt) {
    }

    /**
     * 单个产品的设备统计。
     *
     * @param onlineCount 在线设备数；Redis 不可用时为 null
     */
    public record ProductStat(String productKey, String name, int deviceCount, Integer onlineCount) {
    }
}
```

- [ ] **Step 4: Implement the dead-letter reader**

Create `conduit-server/src/main/java/dev/conduit/server/overview/DeadLetterReader.java`:

```java
package dev.conduit.server.overview;

import dev.conduit.common.bus.MessageBusProperties;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信流读取：从最新一条往回读（XREVRANGE），直到给定时间为止，最多读 limit 条。
 * 死信流的消息 ID 毫秒部分即写入死信（failedAt）的 Redis 服务器时间，因此按 ID 截止即按时间截止。
 */
@Component
public class DeadLetterReader {

    private final StringRedisTemplate redis;
    private final String deadKey;

    public DeadLetterReader(StringRedisTemplate redis, MessageBusProperties bus) {
        this.redis = redis;
        this.deadKey = bus.deadKey();
    }

    /**
     * 读取 ID 时间不早于 sinceMillis 的死信，最新的在前。
     *
     * @param sinceMillis 截止时间（毫秒，含）
     * @param limit       最多读取的条数
     * @return 每条死信的字段（pk、dk、reason、failedAt 等）；死信流不存在时为空列表
     * @throws org.springframework.dao.DataAccessException Redis 不可用时，由调用方处理
     */
    public List<Map<String, String>> since(long sinceMillis, int limit) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(deadKey,
                Range.rightUnbounded(Range.Bound.inclusive(sinceMillis + "-0")), Limit.limit().count(limit));
        if (records == null) {
            return List.of();
        }
        return records.stream().map(r -> {
            Map<String, String> fields = new LinkedHashMap<>();
            r.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
            return fields;
        }).toList();
    }
}
```

- [ ] **Step 5: Implement the service**

Create `conduit-server/src/main/java/dev/conduit/server/overview/OverviewService.java`:

```java
package dev.conduit.server.overview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceStats;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.overview.OverviewDtos.DeadSummary;
import dev.conduit.server.overview.OverviewDtos.OverviewView;
import dev.conduit.server.overview.OverviewDtos.ProductStat;
import dev.conduit.server.overview.OverviewDtos.RecentDevice;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.thingmodel.ProductPropertyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 首页总览：按当前租户统计产品、属性、设备（启用/禁用/在线）、最近上报的设备、各产品设备数与最近 1 小时死信。
 * 产品、属性与设备计数来自 PostgreSQL（租户拦截器过滤）；在线数、最后上报时间与死信来自 Redis，
 * 这三项各自容错：Redis 不可用时对应字段为 null（最近上报列表为空），其余照常返回。
 * 本期把租户设备全部读出再统计，设备到万级时改为增量计数（见 docs/backlog-v0.2.md）。
 */
@Service
public class OverviewService {

    static final Duration DEAD_WINDOW = Duration.ofHours(1);
    static final int DEAD_SCAN_LIMIT = 10_000;
    static final int RECENT_LIMIT = 5;
    private static final String UNKNOWN_REASON = "UNKNOWN";
    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final ProductMapper products;
    private final ProductPropertyMapper properties;
    private final DeviceStats stats;
    private final LatestValueStore latest;
    private final DeadLetterReader deadLetters;
    private final Clock clock;

    public OverviewService(ProductMapper products, ProductPropertyMapper properties, DeviceStats stats,
                           LatestValueStore latest, DeadLetterReader deadLetters, Clock clock) {
        this.products = products;
        this.properties = properties;
        this.stats = stats;
        this.latest = latest;
        this.deadLetters = deadLetters;
        this.clock = clock;
    }

    /**
     * 组装当前租户的总览（只读）。
     *
     * @return 总览；Redis 不可用时 onlineCount、products[].onlineCount、deadLastHour 为 null，recentDevices 为空
     */
    public OverviewView overview() {
        List<Product> productList = products.selectList(new LambdaQueryWrapper<Product>().orderByDesc(Product::getId));
        int propertyCount = Math.toIntExact(properties.selectCount(null));
        DeviceStats.Snapshot snapshot = stats.snapshot(null);
        Map<Long, Product> productsById = productList.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        int deviceCount = snapshot.deviceCount();
        int enabledCount = snapshot.enabledCount();
        return new OverviewView(productList.size(), propertyCount, deviceCount, enabledCount,
                deviceCount - enabledCount, snapshot.onlineCount(), deadLastHour(productList),
                recentDevices(snapshot.devices(), productsById), productStats(productList, snapshot));
    }

    /**
     * 最近 1 小时的死信：只统计 pk 属于当前租户产品的条目，按原因分组。死信流读不到（Redis 不可用）时返回 null。
     */
    private DeadSummary deadLastHour(List<Product> productList) {
        Set<String> productKeys = productList.stream().map(Product::getProductKey).collect(Collectors.toSet());
        List<Map<String, String>> records;
        try {
            records = deadLetters.since(clock.millis() - DEAD_WINDOW.toMillis(), DEAD_SCAN_LIMIT);
        } catch (DataAccessException e) {
            log.warn("Dead-letter stream unavailable, deadLastHour omitted", e);
            return null;
        }
        return summarize(records, productKeys);
    }

    /**
     * 统计属于给定产品的死信条数并按原因分组：没有 pk 或 pk 不在 productKeys 中的条目跳过；原因缺失记为 UNKNOWN；
     * 结果的原因按名称排序。
     */
    static DeadSummary summarize(List<Map<String, String>> records, Set<String> productKeys) {
        Map<String, Integer> byReason = new TreeMap<>();
        int total = 0;
        for (Map<String, String> record : records) {
            String productKey = record.get("pk");
            if (productKey == null || !productKeys.contains(productKey)) {
                continue;
            }
            byReason.merge(record.getOrDefault("reason", UNKNOWN_REASON), 1, Integer::sum);
            total++;
        }
        return new DeadSummary(total, byReason);
    }

    /**
     * 最后上报时间最近的设备（最多 RECENT_LIMIT 台，新的在前；从未上报的不列出）。
     * 最后上报时间读不到（Redis 不可用）时返回空列表。
     */
    private List<RecentDevice> recentDevices(List<Device> devices, Map<Long, Product> productsById) {
        if (devices.isEmpty()) {
            return List.of();
        }
        Map<String, Long> times;
        try {
            times = latest.lastReportTimes(devices.stream().map(Device::getDeviceKey).toList());
        } catch (DataAccessException e) {
            log.warn("Latest-value store unavailable, recentDevices omitted", e);
            return List.of();
        }
        return devices.stream()
                .filter(d -> times.containsKey(d.getDeviceKey()))
                .sorted(Comparator.comparing((Device d) -> times.get(d.getDeviceKey())).reversed())
                .limit(RECENT_LIMIT)
                .map(d -> {
                    Product p = productsById.get(d.getProductId());
                    return new RecentDevice(d.getDeviceKey(), d.getName(), p == null ? null : p.getProductKey(),
                            p == null ? null : p.getName(), times.get(d.getDeviceKey()));
                })
                .toList();
    }

    /**
     * 每个产品的设备数与在线数，按设备数降序；设备数相同的保持产品 id 倒序（新的在前，排序是稳定的）。
     */
    private static List<ProductStat> productStats(List<Product> productList, DeviceStats.Snapshot snapshot) {
        return productList.stream()
                .map(p -> {
                    ProductCounts c = snapshot.of(p.getId());
                    return new ProductStat(p.getProductKey(), p.getName(), c.deviceCount(), c.onlineCount());
                })
                .sorted(Comparator.comparingInt(ProductStat::deviceCount).reversed())
                .toList();
    }
}
```

- [ ] **Step 6: Run the unit test to verify it passes**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=OverviewServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (5 tests).

- [ ] **Step 7: Write the failing reader and API integration tests**

Create `conduit-server/src/test/java/dev/conduit/server/overview/DeadLetterReaderIT.java`:

```java
package dev.conduit.server.overview;

import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DeadLetterReader 集成测试（真实 Redis）：按消息 ID 时间截止、最新在前、条数上限、流不存在时为空。 */
class DeadLetterReaderIT extends IntegrationTestBase {

    /** 用显式 ID 写一条死信，使测试能构造 1 小时以前的条目（独立的流 key，ID 递增写入）。 */
    private void add(String key, long millis, String reason) {
        redis.opsForStream().add(StreamRecords.newRecord().in(key).withId(RecordId.of(millis + "-0"))
                .ofMap(Map.of("pk", "pk-" + reason, "reason", reason)));
    }

    @Test
    void readsNewestFirstBackToTheCutoffWithinTheLimit() {
        String key = unique("test:dead:");
        long now = System.currentTimeMillis();
        add(key, now - 7_200_000, "old");
        add(key, now - 60_000, "a");
        add(key, now - 30_000, "b");
        add(key, now - 10_000, "c");
        DeadLetterReader reader = new DeadLetterReader(redis, new MessageBusProperties("test:up", key, 1000, 1000));

        List<Map<String, String>> all = reader.since(now - 3_600_000, 10);
        assertThat(all).extracting((Map<String, String> r) -> r.get("reason")).containsExactly("c", "b", "a");
        assertThat(all.getFirst()).containsEntry("pk", "pk-c");
        assertThat(reader.since(now - 3_600_000, 2)).extracting((Map<String, String> r) -> r.get("reason"))
                .containsExactly("c", "b");
        redis.delete(key);
    }

    @Test
    void missingStreamGivesAnEmptyList() {
        DeadLetterReader reader = new DeadLetterReader(redis,
                new MessageBusProperties("test:up", unique("test:none:"), 1000, 1000));

        assertThat(reader.since(System.currentTimeMillis() - 3_600_000, 10)).isEmpty();
    }
}
```

Create `conduit-server/src/test/java/dev/conduit/server/overview/OverviewApiIT.java`:

```java
package dev.conduit.server.overview;

import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.auth.JwtService;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 总览接口集成测试：两个新建租户各自只看到自己的产品、属性、设备、在线数、最近上报设备与死信；未登录 401。
 * 两个租户都是新建的，计数不受共享库中其他测试数据的影响。
 */
class OverviewApiIT extends IntegrationTestBase {

    @Autowired
    JwtService jwt;

    @Autowired
    PresenceStore presence;

    @Autowired
    LatestValueStore latest;

    @Autowired
    MessageBusProperties bus;

    /** 测试产品：id 与 productKey。 */
    record TestProduct(long id, String key) {
    }

    @Test
    void eachTenantSeesOnlyItsOwnData() throws Exception {
        long tenantA = createTenant("ov-a-");
        long tenantB = createTenant("ov-b-");
        TestProduct meter = insertProduct(tenantA, "三相电表（总览测试）");
        TestProduct water = insertProduct(tenantA, "水表（总览测试）");
        TestProduct other = insertProduct(tenantB, "其他租户产品（总览测试）");
        insertProperty(meter.key(), "Ua", "A 相电压", "DOUBLE", "V", null, null, 1, null);
        insertProperty(meter.key(), "Ub", "B 相电压", "DOUBLE", "V", null, null, 1, null);
        insertProperty(water.key(), "flow", "瞬时流量", "DOUBLE", "m³/h", null, null, 2, null);
        String m1 = insertDevice(tenantA, meter.id(), "1号楼配电室 电表01", "ENABLED");
        String m2 = insertDevice(tenantA, meter.id(), "1号楼配电室 电表02", "ENABLED");
        String w1 = insertDevice(tenantA, water.id(), "1号楼 水表01", "DISABLED");
        String o1 = insertDevice(tenantB, other.id(), "其他租户设备", "ENABLED");
        long now = System.currentTimeMillis();
        presence.apply(m1, true, now, "");
        presence.apply(m2, false, now, "");
        presence.apply(o1, true, now, "");
        latest.update(m1, now - 5000, Map.of("Ua", 220.0));
        latest.update(w1, now - 1000, Map.of("flow", 1.5));
        latest.update(o1, now, Map.of("Ua", 230.0));
        dead(meter.key(), "FIELD_REJECTED");
        dead(meter.key(), "FIELD_REJECTED");
        dead(water.key(), "MALFORMED_JSON");
        dead(other.key(), "PROCESSING_ERROR");

        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, tokenFor(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(2))
                .andExpect(jsonPath("$.propertyCount").value(3))
                .andExpect(jsonPath("$.deviceCount").value(3))
                .andExpect(jsonPath("$.enabledCount").value(2))
                .andExpect(jsonPath("$.disabledCount").value(1))
                .andExpect(jsonPath("$.onlineCount").value(1))
                .andExpect(jsonPath("$.deadLastHour.total").value(3))
                .andExpect(jsonPath("$.deadLastHour.byReason.FIELD_REJECTED").value(2))
                .andExpect(jsonPath("$.deadLastHour.byReason.MALFORMED_JSON").value(1))
                .andExpect(jsonPath("$.deadLastHour.byReason.PROCESSING_ERROR").doesNotExist())
                .andExpect(jsonPath("$.recentDevices[*].deviceKey", contains(w1, m1)))
                .andExpect(jsonPath("$.recentDevices[0].productName").value("水表（总览测试）"))
                .andExpect(jsonPath("$.products[*].productKey", contains(meter.key(), water.key())))
                .andExpect(jsonPath("$.products[0].deviceCount").value(2))
                .andExpect(jsonPath("$.products[0].onlineCount").value(1))
                .andExpect(jsonPath("$.products[1].onlineCount").value(0));

        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, tokenFor(tenantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(1))
                .andExpect(jsonPath("$.propertyCount").value(0))
                .andExpect(jsonPath("$.deviceCount").value(1))
                .andExpect(jsonPath("$.onlineCount").value(1))
                .andExpect(jsonPath("$.deadLastHour.total").value(1))
                .andExpect(jsonPath("$.deadLastHour.byReason.PROCESSING_ERROR").value(1))
                .andExpect(jsonPath("$.recentDevices[*].deviceKey", contains(o1)))
                .andExpect(jsonPath("$.products[*].productKey", contains(other.key())));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/overview")).andExpect(status().isUnauthorized());
    }

    /** 为指定租户签发 access token（过滤器只校验令牌、不查用户，因此不必建测试用户）。 */
    private String tokenFor(long tenantId) {
        return bearer(jwt.issue(new AuthPrincipal(0L, tenantId, "overview-it", "ADMIN"), JwtService.TokenType.ACCESS));
    }

    /** 直接写库建产品，返回 id 与 productKey。 */
    private TestProduct insertProduct(long tenantId, String name) {
        String key = unique("pk");
        Long id = jdbc.queryForObject("insert into product (tenant_id, product_key, name) values (?, ?, ?) returning id",
                Long.class, tenantId, key, name);
        return new TestProduct(id, key);
    }

    /** 直接写库在指定产品下建设备，返回 deviceKey。 */
    private String insertDevice(long tenantId, long productId, String name, String status) {
        String deviceKey = unique("dk");
        String salt = secretHasher.newSalt();
        jdbc.update("insert into device (tenant_id, product_id, device_key, name, secret_hash, secret_salt, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                tenantId, productId, deviceKey, name, secretHasher.hash("secret-" + deviceKey, salt), salt, status);
        return deviceKey;
    }

    /** 往死信流追加一条记录（自动 ID，即当前 Redis 时间）。 */
    private void dead(String productKey, String reason) {
        redis.opsForStream().add(bus.deadKey(), Map.of("pk", productKey, "dk", "dk-overview-it", "reason", reason,
                "failedAt", Long.toString(System.currentTimeMillis())));
    }
}
```

- [ ] **Step 8: Run them to verify they fail**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test='DeadLetterReaderIT,OverviewApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: `DeadLetterReaderIT` PASS (the reader exists since Step 4; it pins the Redis behavior) and `OverviewApiIT.eachTenantSeesOnlyItsOwnData` FAIL with status `404` (no controller yet). `requiresAuthentication` already passes (security rejects every unauthenticated `/api/v1/**`).

- [ ] **Step 9: Implement the controller**

Create `conduit-server/src/main/java/dev/conduit/server/overview/OverviewController.java`:

```java
package dev.conduit.server.overview;

import dev.conduit.server.overview.OverviewDtos.OverviewView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页总览 REST 接口（GET /api/v1/overview），只读、按当前登录租户统计；逻辑在 OverviewService。
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final OverviewService service;

    public OverviewController(OverviewService service) {
        this.service = service;
    }

    /** 当前租户的总览。 */
    @GetMapping
    public OverviewView get() {
        return service.overview();
    }
}
```

- [ ] **Step 10: Run the integration tests and the whole server module**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test='DeadLetterReaderIT,OverviewApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: PASS.
Run: `mvn -B -pl conduit-server -am verify`
Expected: BUILD SUCCESS. If an image pull on the shared Docker host is slow, wait; do not change images.

- [ ] **Step 11: Commit**

```bash
git add conduit-server/src/main/java/dev/conduit/server/overview \
  conduit-server/src/test/java/dev/conduit/server/overview
git commit -m "feat(server): per-tenant overview API with dead letters of the last hour"
```

---
## Task 3: Frontend foundation: design tokens, appearance preferences, dark mode and chart theme

**Files:**
- Create: `conduit-ui/src/styles/tokens.css`
- Create: `conduit-ui/src/stores/ui.ts`
- Create: `conduit-ui/src/composables/useTheme.ts`
- Create: `conduit-ui/src/components/AppearancePanel.vue`
- Modify: `conduit-ui/src/main.ts` (full replacement below)
- Modify: `conduit-ui/src/utils/historyChart.ts` (one line)
- Modify: `conduit-ui/src/components/HistoryPanel.vue` (edits below)
- Test: `conduit-ui/src/stores/ui.spec.ts` (new)
- Test: `conduit-ui/src/composables/useTheme.spec.ts` (new)
- Test: `conduit-ui/src/components/AppearancePanel.spec.ts` (new)
- Test: `conduit-ui/src/utils/historyChart.spec.ts` (one new test)
- Test: `conduit-ui/src/components/HistoryPanel.spec.ts` (one new test)

**Interfaces:**
- Consumes: Pinia `defineStore`; `echarts` and `type ECharts` from `utils/echarts.ts`; existing `HistoryPanel` internals (`charts`, `disposeCharts()`, `renderCharts()`), `buildChartOption` in `utils/historyChart.ts`; `settle` from `test/viewTestUtils.ts`.
- Produces (used by Tasks 4–7):
  - `stores/ui.ts`: `type ThemePref = 'light' | 'dark' | 'system'`, `type SidebarStyle = 'light' | 'dark'`, `type LatestView = 'cards' | 'table'`, `interface UiPrefs { theme; sidebarStyle; sidebarCollapsed; latestView }`, `UI_STORAGE_KEY = 'conduit.ui'`, `DEFAULT_PREFS`, `parsePrefs(raw: string | null): UiPrefs`, `loadPrefs(): UiPrefs`, `savePrefs(prefs: UiPrefs): void`, `useUiStore` with state `UiPrefs` and actions `setTheme(t)`, `setSidebarStyle(s)`, `setSidebarCollapsed(b)`, `toggleSidebar()`, `setLatestView(v)`, `persist()`.
  - `composables/useTheme.ts`: `resolveDark(pref: ThemePref, systemDark: boolean): boolean`, `applyTheme(isDark: boolean): void`, `chartTheme(isDark: boolean): 'dark' | undefined`, `installTheme(pref: () => ThemePref): () => void`, `useTheme(): { isDark: Readonly<Ref<boolean>> }`.
  - `<AppearancePanel />` (no props; reads and writes `useUiStore`).
  - CSS classes from `tokens.css`: `.cd-panel`, `.cd-pager`, `.cd-muted`; variables `--cd-space-1..5`, `--cd-radius-sm|radius|radius-lg`, `--cd-header-height`, `--cd-page-bg`, `--cd-panel-bg`, `--cd-panel-border`, `--cd-shadow`, `--cd-text-muted`, `--cd-online`, `--cd-offline`, `--cd-brand-bg|text|muted`, `--cd-sidebar-width`, `--cd-sidebar-collapsed-width`, `--cd-sidebar-{dark|light}-{bg|text|muted|hover|active-bg|active-text|border}`.

**Decisions recorded here:**
- The primary color stays Element Plus's own `--el-color-primary` (`#409eff`). Overriding it with a CSS variable alone would not update the `-light-N`/`-dark-2` shades Element derives at build time, so "one blue" is achieved by not overriding it and by never writing literal colors in app styles.
- `useTheme` keeps the effective "is dark" flag in a module-level ref, not in the Pinia store. Chart components (`HistoryPanel`, `Sparkline`) read it through `useTheme()` and therefore need no Pinia in their specs. `installTheme` runs once in `main.ts` before `mount`, so the first paint already has the right class (no light flash).
- Charts are rebuilt (dispose + `echarts.init(el, 'dark' | undefined)`) on a theme change. ECharts cannot switch the theme of a live instance, and re-init keeps the code path identical to the first render. Every option sets `backgroundColor: 'transparent'`, because the built-in `dark` theme paints its own dark-blue background, which would not match the Element Plus dark surface.
- Unknown or invalid stored values fall back per field (an invalid `sidebarStyle` does not reset a valid `theme`).
- The sidebar "auto-collapse below 820px" is not a stored preference: Task 4 derives it from a media query, so a narrow window never overwrites the user's saved choice.

- [ ] **Step 1: Write the failing store, theme and panel specs**

Create `conduit-ui/src/stores/ui.spec.ts`:

```ts
/** ui store 的单元测试：默认值、读取已存偏好、每次修改写入 conduit.ui、读写 localStorage 失败时的容错 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { DEFAULT_PREFS, parsePrefs, UI_STORAGE_KEY, useUiStore } from './ui'

describe('parsePrefs', () => {
  it.each([null, '', 'not json', '[]', '42', 'null'])('returns the defaults for %s', (raw) => {
    expect(parsePrefs(raw)).toEqual(DEFAULT_PREFS)
  })

  it('keeps valid fields and replaces invalid ones with defaults', () => {
    const raw = JSON.stringify({ theme: 'dark', sidebarStyle: 'purple', sidebarCollapsed: 'yes', latestView: 'table' })

    expect(parsePrefs(raw)).toEqual({ theme: 'dark', sidebarStyle: 'dark', sidebarCollapsed: false, latestView: 'table' })
  })
})

describe('useUiStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts with system theme, dark sidebar, expanded menu and the card view', () => {
    const ui = useUiStore()

    expect(ui.theme).toBe('system')
    expect(ui.sidebarStyle).toBe('dark')
    expect(ui.sidebarCollapsed).toBe(false)
    expect(ui.latestView).toBe('cards')
  })

  it('loads saved preferences', () => {
    localStorage.setItem(UI_STORAGE_KEY, JSON.stringify({ theme: 'light', sidebarStyle: 'light', sidebarCollapsed: true, latestView: 'table' }))

    const ui = useUiStore()

    expect(ui.theme).toBe('light')
    expect(ui.sidebarStyle).toBe('light')
    expect(ui.sidebarCollapsed).toBe(true)
    expect(ui.latestView).toBe('table')
  })

  it('saves every change under conduit.ui', () => {
    const ui = useUiStore()

    ui.setTheme('dark')
    ui.setSidebarStyle('light')
    ui.toggleSidebar()
    ui.setLatestView('table')

    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!)).toEqual({
      theme: 'dark',
      sidebarStyle: 'light',
      sidebarCollapsed: true,
      latestView: 'table',
    })
  })

  it('falls back to the defaults when localStorage cannot be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })

    expect(useUiStore().$state).toEqual(DEFAULT_PREFS)
  })

  it('keeps working when localStorage cannot be written', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    const ui = useUiStore()

    expect(() => ui.setTheme('dark')).not.toThrow()
    expect(ui.theme).toBe('dark')
  })
})
```

Create `conduit-ui/src/composables/useTheme.spec.ts`:

```ts
/** useTheme 的单元测试：偏好与系统设置的合成、跟随系统实时切换、手动选择写入 <html> 的 dark 类、无 matchMedia 环境 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import type { ThemePref } from '@/stores/ui'
import { applyTheme, chartTheme, installTheme, resolveDark, useTheme } from './useTheme'

/** 安装一个可控的 matchMedia：matches 可改，fire() 触发 change 监听 */
function fakeMedia(matches: boolean) {
  const listeners = new Set<() => void>()
  const mql = {
    matches,
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }
  window.matchMedia = vi.fn(() => mql as unknown as MediaQueryList)
  return { mql, listeners, fire: () => listeners.forEach((l) => l()) }
}

const isDarkClass = () => document.documentElement.classList.contains('dark')

describe('useTheme', () => {
  afterEach(() => {
    applyTheme(false)
    Reflect.deleteProperty(window, 'matchMedia')
  })

  it('resolves the preference against the system setting', () => {
    expect(resolveDark('system', true)).toBe(true)
    expect(resolveDark('system', false)).toBe(false)
    expect(resolveDark('dark', false)).toBe(true)
    expect(resolveDark('light', true)).toBe(false)
  })

  it('follows the system setting while the preference is system', () => {
    const media = fakeMedia(true)

    const stop = installTheme(() => 'system')
    expect(isDarkClass()).toBe(true)
    expect(useTheme().isDark.value).toBe(true)

    media.mql.matches = false
    media.fire()
    expect(isDarkClass()).toBe(false)
    expect(useTheme().isDark.value).toBe(false)

    stop()
    expect(media.listeners.size).toBe(0)
  })

  it('applies a manual choice and ignores system changes', async () => {
    const media = fakeMedia(true)
    const pref = ref<ThemePref>('light')

    const stop = installTheme(() => pref.value)
    expect(isDarkClass()).toBe(false)

    pref.value = 'dark'
    await nextTick()
    expect(isDarkClass()).toBe(true)

    media.mql.matches = false
    media.fire()
    expect(isDarkClass()).toBe(true)
    stop()
  })

  it('treats a missing matchMedia as a light system', () => {
    const stop = installTheme(() => 'system')

    expect(isDarkClass()).toBe(false)
    stop()
  })

  it('maps the theme to the ECharts theme name', () => {
    expect(chartTheme(true)).toBe('dark')
    expect(chartTheme(false)).toBeUndefined()
  })
})
```

Create `conduit-ui/src/components/AppearancePanel.spec.ts`:

```ts
/** AppearancePanel 组件测试：显示当前偏好，切换主题与侧栏风格后写入 store 与 localStorage */
import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import AppearancePanel from './AppearancePanel.vue'
import { UI_STORAGE_KEY, useUiStore } from '@/stores/ui'

describe('AppearancePanel', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('shows the current preferences and saves changes', async () => {
    const wrapper = mount(AppearancePanel, { global: { plugins: [ElementPlus] } })
    const groups = wrapper.findAllComponents(ElRadioGroup)

    expect(wrapper.text()).toContain('跟随系统')
    expect(groups[0].props('modelValue')).toBe('system')
    expect(groups[1].props('modelValue')).toBe('dark')

    groups[0].vm.$emit('change', 'dark')
    groups[1].vm.$emit('change', 'light')
    await nextTick()

    const ui = useUiStore()
    expect(ui.theme).toBe('dark')
    expect(ui.sidebarStyle).toBe('light')
    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!)).toMatchObject({ theme: 'dark', sidebarStyle: 'light' })
    wrapper.unmount()
  })
})
```

In `conduit-ui/src/utils/historyChart.spec.ts`, append at the end of the file:

```ts
describe('chart background', () => {
  it('is transparent so the ECharts dark theme blends with the page', () => {
    const o = buildChartOption(series(), RAW_CTX) as { backgroundColor?: string }

    expect(o.backgroundColor).toBe('transparent')
  })
})
```

In `conduit-ui/src/components/HistoryPanel.spec.ts`:

1. Add the import `import { applyTheme } from '@/composables/useTheme'` below the `import { echarts } from '@/utils/echarts'` line.
2. Insert this test before the file's final `})`:

```ts
  it('rebuilds the charts with the ECharts dark theme when the theme changes', async () => {
    const wrapper = await mountPanel()
    const first = fake.charts[0]
    expect(vi.mocked(echarts.init).mock.calls[0][1]).toBeUndefined()

    applyTheme(true)
    await settle()

    expect(first.dispose).toHaveBeenCalled()
    expect(fake.charts).toHaveLength(2)
    expect(vi.mocked(echarts.init).mock.calls[1][1]).toBe('dark')
    expect(fake.charts[1].setOption).toHaveBeenCalled()

    applyTheme(false)
    await settle()
    wrapper.unmount()
  })
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/stores/ui.spec.ts src/composables/useTheme.spec.ts src/components/AppearancePanel.spec.ts src/utils/historyChart.spec.ts src/components/HistoryPanel.spec.ts ; cd ..`
Expected: FAIL — `Failed to resolve import "./ui"`, `"./useTheme"`, `"./AppearancePanel.vue"`, `"@/composables/useTheme"`; `historyChart` background test fails with `expected undefined to be 'transparent'`.

- [ ] **Step 3: Add the design tokens**

Create `conduit-ui/src/styles/tokens.css`:

```css
/*
 * Conduit 设计变量（--cd-*）与全局基础样式。
 * 颜色优先引用 Element Plus 变量（--el-*），它们在 html.dark 下由 element-plus/theme-chalk/dark/css-vars.css 切换；
 * 这里只定义 Element Plus 没有的值（间距、圆角、侧栏两种风格、登录页品牌区），并在 html.dark 下给出深色值。
 * 应用组件的样式只使用这些变量，不写死颜色。
 */
:root {
  --cd-space-1: 4px;
  --cd-space-2: 8px;
  --cd-space-3: 12px;
  --cd-space-4: 16px;
  --cd-space-5: 24px;
  --cd-radius-sm: 4px;
  --cd-radius: 8px;
  --cd-radius-lg: 12px;
  --cd-header-height: 56px;

  --cd-page-bg: var(--el-bg-color-page);
  --cd-panel-bg: var(--el-bg-color);
  --cd-panel-border: var(--el-border-color-lighter);
  --cd-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  --cd-text-muted: var(--el-text-color-secondary);
  --cd-online: var(--el-color-success);
  --cd-offline: var(--el-text-color-placeholder);

  /* 登录页左侧品牌区（浅色、深色主题下都是深色） */
  --cd-brand-bg: #0f1c30;
  --cd-brand-text: #e5ecf6;
  --cd-brand-muted: #94a3b8;

  --cd-sidebar-width: 208px;
  --cd-sidebar-collapsed-width: 64px;
  /* 侧栏深色风格：深蓝灰底，选中项主色底白字 */
  --cd-sidebar-dark-bg: #1b2636;
  --cd-sidebar-dark-text: #c5cdd9;
  --cd-sidebar-dark-muted: #7c8899;
  --cd-sidebar-dark-hover: #243247;
  --cd-sidebar-dark-active-bg: var(--el-color-primary);
  --cd-sidebar-dark-active-text: #ffffff;
  --cd-sidebar-dark-border: transparent;
  /* 侧栏浅色风格：白底，选中项浅蓝底蓝字（随主题切换） */
  --cd-sidebar-light-bg: var(--el-bg-color);
  --cd-sidebar-light-text: var(--el-text-color-regular);
  --cd-sidebar-light-muted: var(--el-text-color-secondary);
  --cd-sidebar-light-hover: var(--el-fill-color-light);
  --cd-sidebar-light-active-bg: var(--el-color-primary-light-9);
  --cd-sidebar-light-active-text: var(--el-color-primary);
  --cd-sidebar-light-border: var(--el-border-color-light);
}

html.dark {
  --cd-shadow: none;
  --cd-brand-bg: #0a1220;
  --cd-sidebar-dark-bg: #111a26;
  --cd-sidebar-dark-hover: #1b2738;
}

html,
body {
  margin: 0;
}

body {
  background: var(--cd-page-bg);
  color: var(--el-text-color-primary);
  font-family: var(--el-font-family);
}

a {
  color: var(--el-color-primary);
  text-decoration: none;
}

a:hover {
  text-decoration: underline;
}

/* 内容面板：列表与卡片的统一底色、边框、圆角与内边距 */
.cd-panel {
  background: var(--cd-panel-bg);
  border: 1px solid var(--cd-panel-border);
  border-radius: var(--cd-radius);
  box-shadow: var(--cd-shadow);
  padding: var(--cd-space-4);
}

/* 表格下方的分页器：右对齐 */
.cd-pager {
  margin-top: var(--cd-space-4);
  justify-content: flex-end;
}

/* 次要说明文字 */
.cd-muted {
  color: var(--cd-text-muted);
  font-size: 12px;
}
```

- [ ] **Step 4: Implement the preference store**

Create `conduit-ui/src/stores/ui.ts`:

```ts
/**
 * 外观偏好（Pinia）：主题（浅色/深色/跟随系统）、侧栏风格（浅色/深色）、侧栏折叠、设备最新值视图（卡片/表格）。
 * 存在 localStorage 键 conduit.ui；读写失败（隐私模式、被禁用、配额满）时使用默认值，不报错。
 */
import { defineStore } from 'pinia'

export type ThemePref = 'light' | 'dark' | 'system'
export type SidebarStyle = 'light' | 'dark'
export type LatestView = 'cards' | 'table'

/** 全部外观偏好 */
export interface UiPrefs {
  theme: ThemePref
  sidebarStyle: SidebarStyle
  sidebarCollapsed: boolean
  latestView: LatestView
}

export const UI_STORAGE_KEY = 'conduit.ui'

/** 默认偏好：跟随系统、深色侧栏、侧栏展开、卡片视图 */
export const DEFAULT_PREFS: Readonly<UiPrefs> = Object.freeze({
  theme: 'system',
  sidebarStyle: 'dark',
  sidebarCollapsed: false,
  latestView: 'cards',
})

const THEMES: readonly ThemePref[] = ['light', 'dark', 'system']
const SIDEBAR_STYLES: readonly SidebarStyle[] = ['light', 'dark']
const LATEST_VIEWS: readonly LatestView[] = ['cards', 'table']

/** 值在允许范围内则原样返回，否则返回默认值 */
function pick<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback
}

/**
 * 解析 localStorage 中的 JSON：整体无法解析或不是对象时返回默认值；逐字段校验，无效字段取默认值，不影响其他字段。
 * @param raw localStorage 原始值；没有存过时为 null
 */
export function parsePrefs(raw: string | null): UiPrefs {
  let data: Record<string, unknown> = {}
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        data = parsed as Record<string, unknown>
      }
    } catch {
      // 无法解析时用默认值
    }
  }
  return {
    theme: pick(data.theme, THEMES, DEFAULT_PREFS.theme),
    sidebarStyle: pick(data.sidebarStyle, SIDEBAR_STYLES, DEFAULT_PREFS.sidebarStyle),
    sidebarCollapsed: typeof data.sidebarCollapsed === 'boolean' ? data.sidebarCollapsed : DEFAULT_PREFS.sidebarCollapsed,
    latestView: pick(data.latestView, LATEST_VIEWS, DEFAULT_PREFS.latestView),
  }
}

/** 读取偏好；localStorage 不可用或读取抛错时返回默认值 */
export function loadPrefs(): UiPrefs {
  try {
    return parsePrefs(window.localStorage.getItem(UI_STORAGE_KEY))
  } catch {
    return { ...DEFAULT_PREFS }
  }
}

/** 保存偏好；写入失败时静默忽略（本次会话内偏好仍然生效） */
export function savePrefs(prefs: UiPrefs): void {
  try {
    window.localStorage.setItem(UI_STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // 忽略
  }
}

export const useUiStore = defineStore('ui', {
  state: (): UiPrefs => loadPrefs(),
  actions: {
    /** 设置主题偏好并保存 */
    setTheme(theme: ThemePref) {
      this.theme = theme
      this.persist()
    },
    /** 设置侧栏风格并保存 */
    setSidebarStyle(style: SidebarStyle) {
      this.sidebarStyle = style
      this.persist()
    },
    /** 设置侧栏是否折叠并保存 */
    setSidebarCollapsed(collapsed: boolean) {
      this.sidebarCollapsed = collapsed
      this.persist()
    },
    /** 切换侧栏折叠状态并保存 */
    toggleSidebar() {
      this.setSidebarCollapsed(!this.sidebarCollapsed)
    },
    /** 设置设备最新值视图（卡片/表格）并保存 */
    setLatestView(view: LatestView) {
      this.latestView = view
      this.persist()
    },
    /** 把当前全部偏好写入 localStorage */
    persist() {
      savePrefs({
        theme: this.theme,
        sidebarStyle: this.sidebarStyle,
        sidebarCollapsed: this.sidebarCollapsed,
        latestView: this.latestView,
      })
    },
  },
})
```

- [ ] **Step 5: Implement the theme composable**

Create `conduit-ui/src/composables/useTheme.ts`:

```ts
/**
 * 主题：按外观偏好（浅色/深色/跟随系统）在 <html> 上切换 Element Plus 的 dark 类，并向图表等组件提供当前是否为深色。
 * 「是否深色」放在模块级 ref 中（不放 Pinia），图表组件读取它时不依赖 store。
 */
import { ref, watch, type Ref } from 'vue'
import type { ThemePref } from '@/stores/ui'

const SYSTEM_DARK_QUERY = '(prefers-color-scheme: dark)'
const dark = ref(false)

/**
 * 合成最终主题：跟随系统时取系统设置，否则取偏好本身。
 * @param pref 主题偏好
 * @param systemDark 系统当前是否为深色
 */
export function resolveDark(pref: ThemePref, systemDark: boolean): boolean {
  return pref === 'system' ? systemDark : pref === 'dark'
}

/** 应用主题：更新共享状态，并在 <html> 上添加或移除 dark 类（Element Plus 暗色变量据此生效） */
export function applyTheme(isDark: boolean): void {
  dark.value = isDark
  document.documentElement.classList.toggle('dark', isDark)
}

/** ECharts 主题名：深色用 ECharts 内置的 dark 主题，浅色用默认主题（undefined） */
export function chartTheme(isDark: boolean): 'dark' | undefined {
  return isDark ? 'dark' : undefined
}

/** 系统深色查询；环境不支持 matchMedia（如测试环境）时为 null，视为浅色 */
function systemQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(SYSTEM_DARK_QUERY)
    : null
}

/**
 * 安装主题：立即按偏好应用一次；之后偏好变化，或系统主题变化（只在跟随系统时影响结果）都会重新应用。
 * 在 main.ts 挂载应用之前调用，保证首屏就是正确的主题。
 * @param pref 返回当前主题偏好的函数（通常读 ui store）
 * @returns 卸载函数：停止监听偏好与系统主题
 */
export function installTheme(pref: () => ThemePref): () => void {
  const media = systemQuery()
  const update = () => applyTheme(resolveDark(pref(), media?.matches ?? false))
  const stopWatch = watch(pref, update, { immediate: true })
  media?.addEventListener('change', update)
  return () => {
    stopWatch()
    media?.removeEventListener('change', update)
  }
}

/** 组件内读取当前是否为深色（只读，可被 watch） */
export function useTheme(): { isDark: Readonly<Ref<boolean>> } {
  return { isDark: dark }
}
```

- [ ] **Step 6: Implement the appearance panel**

Create `conduit-ui/src/components/AppearancePanel.vue`:

```vue
<script setup lang="ts">
/**
 * 外观设置面板（顶栏「外观」按钮弹出）：主题 浅色 / 深色 / 跟随系统，侧栏 浅色 / 深色。
 * 选择即时生效（主题由 useTheme 监听 store 应用）并写入外观偏好。
 */
import { useUiStore, type SidebarStyle, type ThemePref } from '@/stores/ui'

const ui = useUiStore()

const THEMES: { value: ThemePref; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]
const SIDEBAR_STYLES: { value: SidebarStyle; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
]

/** 主题单选变化 */
function onTheme(value: unknown) {
  ui.setTheme(value as ThemePref)
}

/** 侧栏风格单选变化 */
function onSidebarStyle(value: unknown) {
  ui.setSidebarStyle(value as SidebarStyle)
}
</script>

<template>
  <div class="appearance">
    <div class="group">
      <div class="label">主题</div>
      <el-radio-group :model-value="ui.theme" size="small" @change="onTheme">
        <el-radio-button v-for="t in THEMES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
    </div>
    <div class="group">
      <div class="label">侧栏</div>
      <el-radio-group :model-value="ui.sidebarStyle" size="small" @change="onSidebarStyle">
        <el-radio-button v-for="s in SIDEBAR_STYLES" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
      </el-radio-group>
    </div>
  </div>
</template>

<style scoped>
.appearance {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-3);
}
.label {
  margin-bottom: var(--cd-space-1);
  color: var(--cd-text-muted);
  font-size: 12px;
}
</style>
```

- [ ] **Step 7: Wire dark CSS, tokens and the theme into the entry point**

Replace `conduit-ui/src/main.ts` with:

```ts
/**
 * 前端入口：创建应用并安装 Pinia、路由与 Element Plus（中文语言包）；引入 Element Plus 暗色变量与应用设计变量，
 * 并在挂载前按外观偏好应用主题（避免首屏闪烁），最后挂载到 #app。
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tokens.css'
import App from './App.vue'
import router from './router'
import { useUiStore } from './stores/ui'
import { installTheme } from './composables/useTheme'

const pinia = createPinia()
const app = createApp(App).use(pinia)
const ui = useUiStore(pinia)
installTheme(() => ui.theme)
app.use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
```

- [ ] **Step 8: Make the history charts follow the theme**

In `conduit-ui/src/utils/historyChart.ts`, inside `buildChartOption`, change

```ts
  const common = {
    animation: false,
```

to

```ts
  const common = {
    animation: false,
    // 透明背景：深色时 ECharts 内置 dark 主题自带深蓝背景，与页面底色不一致
    backgroundColor: 'transparent',
```

In `conduit-ui/src/components/HistoryPanel.vue`:

1. In the header comment, append a sentence at the end of the comment block (before ` */`):

```ts
 * 图表随主题切换：深色时用 ECharts 内置 dark 主题，主题变化时销毁并按新主题重建实例。
```

2. Add the import below `import { createRequestSeq } from '@/utils/requestSeq'`:

```ts
import { chartTheme, useTheme } from '@/composables/useTheme'
```

3. Below `const poller = createVisiblePoller(() => load(currentRange()), REFRESH_MS)`, add:

```ts
const { isDark } = useTheme()
```

4. In `renderCharts()`, change `chart = echarts.init(el)` to:

```ts
      chart = echarts.init(el, chartTheme(isDark.value))
```

5. Directly above `watch(selected, () => schedule())`, add:

```ts
/** 主题切换：ECharts 不能修改已有实例的主题，销毁全部实例后按新主题重新渲染当前数据 */
watch(isDark, () => {
  disposeCharts()
  renderCharts()
})
```

- [ ] **Step 9: Run the specs to verify they pass**

Run: `cd conduit-ui && npx vitest run src/stores/ui.spec.ts src/composables/useTheme.spec.ts src/components/AppearancePanel.spec.ts src/utils/historyChart.spec.ts src/components/HistoryPanel.spec.ts ; cd ..`
Expected: all PASS.

- [ ] **Step 10: Run all frontend tests, the type check and the build**

Run: `cd conduit-ui && npm test && npm run build ; cd ..`
Expected: all specs PASS; `vue-tsc --noEmit` clean; `vite build` succeeds and the CSS bundle contains `html.dark` (`grep -l "html.dark" conduit-ui/dist/assets/*.css` finds a file).

- [ ] **Step 11: Commit**

```bash
git add conduit-ui/src/styles/tokens.css conduit-ui/src/stores/ui.ts conduit-ui/src/stores/ui.spec.ts \
  conduit-ui/src/composables/useTheme.ts conduit-ui/src/composables/useTheme.spec.ts \
  conduit-ui/src/components/AppearancePanel.vue conduit-ui/src/components/AppearancePanel.spec.ts \
  conduit-ui/src/main.ts conduit-ui/src/utils/historyChart.ts conduit-ui/src/utils/historyChart.spec.ts \
  conduit-ui/src/components/HistoryPanel.vue conduit-ui/src/components/HistoryPanel.spec.ts
git commit -m "feat(ui): appearance preferences, dark mode and theme-aware charts"
```

---
## Task 4: Frontend shell: sidebar, top bar, breadcrumbs, document title, 404 and PageHeader

**Files:**
- Create: `conduit-ui/src/router/meta.ts`
- Modify: `conduit-ui/src/router/index.ts` (full replacement below)
- Create: `conduit-ui/src/composables/useBreadcrumb.ts`
- Create: `conduit-ui/src/composables/useMediaQuery.ts`
- Create: `conduit-ui/src/layouts/menu.ts`
- Modify: `conduit-ui/src/layouts/AppLayout.vue` (full replacement below)
- Create: `conduit-ui/src/components/PageHeader.vue`
- Create: `conduit-ui/src/views/NotFoundView.vue`
- Modify: `conduit-ui/src/App.vue` (full replacement below)
- Modify: `conduit-ui/src/api/types.ts` (`UserView`)
- Modify: `conduit-ui/src/test/viewTestUtils.ts` (full replacement below)
- Test: `conduit-ui/src/composables/useBreadcrumb.spec.ts`, `conduit-ui/src/layouts/AppLayout.spec.ts`, `conduit-ui/src/components/PageHeader.spec.ts`, `conduit-ui/src/views/NotFoundView.spec.ts` (new)

**Interfaces:**
- Consumes: Task 3 `useUiStore` (`sidebarStyle`, `sidebarCollapsed`, `toggleSidebar()`, `setSidebarStyle()`), `UI_STORAGE_KEY`, `<AppearancePanel />`; existing `useAuthStore` (`user`, `logout()`), `authApi` (mocked in specs); icons from `@element-plus/icons-vue`.
- Produces (used by Tasks 5–7):
  - `router/meta.ts`: `interface BreadcrumbItem { label: string; to?: RouteLocationRaw }`; `RouteMeta` augmentation `{ public?: boolean; title?: string; breadcrumb?: BreadcrumbItem[]; menu?: string }`; `ROUTE_META` with keys `login`, `products`, `product-detail`, `devices`, `device-detail`, `not-found` (Task 7 adds `home`).
  - `composables/useBreadcrumb.ts`: `APP_NAME = 'Conduit'`, `buildBreadcrumb(base, name): BreadcrumbItem[]`, `documentTitle(pageTitle, name): string`, `useBreadcrumb(): { setName(name: string | null): void }` (detail pages), `useCurrentPage(): { breadcrumb: ComputedRef<BreadcrumbItem[]>; title: ComputedRef<string> }` (layout, root).
  - `composables/useMediaQuery.ts`: `useMediaQuery(query: string): Readonly<Ref<boolean>>`.
  - `layouts/menu.ts`: `interface MenuItem { path; label; icon: Component; disabledNote?: string }`, `interface MenuGroup { title; items }`, `MENU_GROUPS`.
  - `<PageHeader title subtitle?>` with slots `subtitle` and `actions`.
  - `views/NotFoundView.vue`; route name `not-found`.
  - `api/types.ts`: `UserView { username: string; role: string; tenantName: string | null }`.
  - `test/viewTestUtils.ts`: `createTestRouter()` now carries `ROUTE_META` and has `login` and `not-found` routes.

**Decisions recorded here:**
- Breadcrumbs, the page title and the highlighted menu all come from route `meta` (`ROUTE_META`), shared by the real router and the test router, so a spec sees the same breadcrumbs as the app. Detail pages add the object name through `useBreadcrumb().setName()`. The name lives in a module-level ref, is cleared when the page unmounts, and a late `setName` from a request that finishes after the page was left is ignored.
- The document title is set in `App.vue` (so the login page gets 「登录 · Conduit」 too), from `useCurrentPage().title`.
- Below 820px the sidebar is forced collapsed and the collapse toggle is hidden; the saved preference is untouched, so widening the window restores it.
- The 404 route is a child catch-all of the layout, so it shows inside the frame for signed-in users. Unauthenticated users go through the login guard first and come back to the 404 page.
- Disabled 用户 / 租户 items are real `el-menu-item`s with `disabled`, so they render with the same icon and spacing, and collapse like the others. Their index paths (`/users`, `/tenants`) are never navigated.
- Task 4 keeps `/` → `/devices` and has no 概览 group. Task 7 adds the home page, the 概览 group and the `/` → `/home` redirect in the same commit, so no intermediate build links to a missing page.

- [ ] **Step 1: Write the failing shell specs**

Create `conduit-ui/src/composables/useBreadcrumb.spec.ts`:

```ts
/** useBreadcrumb 的单元测试：面包屑生成（最后一级不可点击）、浏览器标题、详情页设置对象名称与卸载时清除 */
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import type { BreadcrumbItem } from '@/router/meta'
import { createTestRouter } from '@/test/viewTestUtils'
import { buildBreadcrumb, documentTitle, useBreadcrumb, useCurrentPage } from './useBreadcrumb'

describe('buildBreadcrumb', () => {
  it('appends the object name and drops the link of the last item', () => {
    const base: BreadcrumbItem[] = [{ label: '设备接入' }, { label: '设备', to: { name: 'devices' } }]

    expect(buildBreadcrumb(base, '1号楼配电室 电表01')).toEqual([
      { label: '设备接入' },
      { label: '设备', to: { name: 'devices' } },
      { label: '1号楼配电室 电表01' },
    ])
    expect(buildBreadcrumb(base, null)).toEqual([{ label: '设备接入' }, { label: '设备' }])
    expect(buildBreadcrumb(undefined, null)).toEqual([])
  })
})

describe('documentTitle', () => {
  it('prefers the object name, then the page name, then the app name', () => {
    expect(documentTitle('设备详情', '1号楼配电室 电表01')).toBe('1号楼配电室 电表01 · Conduit')
    expect(documentTitle('产品', null)).toBe('产品 · Conduit')
    expect(documentTitle(undefined, null)).toBe('Conduit')
  })
})

describe('useBreadcrumb and useCurrentPage', () => {
  it('shows the name set by a detail page and clears it when the page unmounts', async () => {
    const router = createTestRouter()
    await router.push('/devices/dk1')
    await router.isReady()
    const Reader = defineComponent({
      setup: () => useCurrentPage(),
      template: '<div />',
    })
    const Setter = defineComponent({
      setup() {
        useBreadcrumb().setName('1号楼配电室 电表01')
        return {}
      },
      template: '<div />',
    })
    const reader = mount(Reader, { global: { plugins: [router] } })
    const setter = mount(Setter, { global: { plugins: [router] } })
    const vm = reader.vm as unknown as { breadcrumb: BreadcrumbItem[]; title: string }

    expect(vm.breadcrumb.map((i) => i.label)).toEqual(['设备接入', '设备', '1号楼配电室 电表01'])
    expect(vm.title).toBe('1号楼配电室 电表01 · Conduit')

    setter.unmount()
    expect(vm.breadcrumb.map((i) => i.label)).toEqual(['设备接入', '设备'])
    expect(vm.title).toBe('设备详情 · Conduit')
    reader.unmount()
  })

  it('ignores a name set after the page was left', async () => {
    const router = createTestRouter()
    await router.push('/products/pk1')
    await router.isReady()
    let setName: (name: string | null) => void = () => {}
    const Setter = defineComponent({
      setup() {
        setName = useBreadcrumb().setName
        return {}
      },
      template: '<div />',
    })
    const Reader = defineComponent({ setup: () => useCurrentPage(), template: '<div />' })
    const setter = mount(Setter, { global: { plugins: [router] } })
    const reader = mount(Reader, { global: { plugins: [router] } })

    setter.unmount()
    setName('迟到的名称')

    expect((reader.vm as unknown as { title: string }).title).toBe('产品详情 · Conduit')
    reader.unmount()
  })
})
```

Create `conduit-ui/src/layouts/AppLayout.spec.ts`:

```ts
/** AppLayout 组件测试：菜单分组与按路由归属高亮、禁用的 V0.2-C 菜单、折叠并保存偏好、侧栏风格类名、面包屑、租户名称与退出登录 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import AppLayout from './AppLayout.vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { UI_STORAGE_KEY, useUiStore } from '@/stores/ui'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn() } }))

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.token = 'token'
  auth.user = { username: 'admin', role: 'ADMIN', tenantName: '默认租户' }
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AppLayout, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

describe('AppLayout', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
  })

  it('groups the menu and highlights 设备 on a device detail page', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    expect(wrapper.text()).toContain('设备接入')
    expect(wrapper.text()).toContain('系统管理')
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('设备')
    wrapper.unmount()
  })

  it('highlights 产品 on a product detail page', async () => {
    const { wrapper } = await mountAt('/products/pk1')

    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('产品')
    wrapper.unmount()
  })

  it('shows 用户 and 租户 disabled with the V0.2-C note', async () => {
    const { wrapper } = await mountAt('/devices')

    const disabled = wrapper.findAll('.el-menu-item.is-disabled')
    expect(disabled).toHaveLength(2)
    expect(disabled[0].text()).toContain('用户')
    expect(disabled[1].text()).toContain('租户')
    disabled.forEach((d) => expect(d.text()).toContain('V0.2-C'))
    wrapper.unmount()
  })

  it('collapses from the toggle and remembers it', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.el-menu').classes()).not.toContain('el-menu--collapse')
    await wrapper.find('.collapse-toggle').trigger('click')
    await settle()

    expect(wrapper.find('.el-menu').classes()).toContain('el-menu--collapse')
    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!).sidebarCollapsed).toBe(true)
    wrapper.unmount()
  })

  it('uses the dark sidebar by default and follows the sidebar style preference', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.sidebar').classes()).toContain('cd-sidebar--dark')
    useUiStore().setSidebarStyle('light')
    await nextTick()

    expect(wrapper.find('.sidebar').classes()).toContain('cd-sidebar--light')
    wrapper.unmount()
  })

  it('shows the breadcrumb from the route meta, the tenant name and the avatar initial', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    const crumbs = wrapper.findAll('.breadcrumb .el-breadcrumb__inner').map((c) => c.text())
    expect(crumbs).toEqual(['设备接入', '设备'])
    expect(wrapper.find('.tenant').text()).toBe('默认租户')
    expect(wrapper.find('.el-avatar').text()).toBe('A')
    wrapper.unmount()
  })

  it('logs out from the user menu and returns to the login page', async () => {
    const { wrapper, router } = await mountAt('/devices')

    ;(wrapper.vm as unknown as { onCommand(command: string): void }).onCommand('logout')
    await settle()

    expect(authApi.logout).toHaveBeenCalledOnce()
    expect(useAuthStore().user).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })
})
```

Create `conduit-ui/src/components/PageHeader.spec.ts`:

```ts
/** PageHeader 组件测试：标题、副标题（属性或插槽）与右侧操作插槽 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from './PageHeader.vue'

describe('PageHeader', () => {
  it('renders the title, the subtitle and the actions', () => {
    const wrapper = mount(PageHeader, {
      props: { title: '产品', subtitle: '一类设备的模板' },
      slots: { actions: '<button>新建产品</button>' },
    })

    expect(wrapper.find('h2').text()).toBe('产品')
    expect(wrapper.find('.subtitle').text()).toBe('一类设备的模板')
    expect(wrapper.find('.actions button').text()).toBe('新建产品')
  })

  it('omits the subtitle and the actions when not given, and accepts a subtitle slot', () => {
    expect(mount(PageHeader, { props: { title: '设备' } }).find('.subtitle').exists()).toBe(false)
    expect(mount(PageHeader, { props: { title: '设备' } }).find('.actions').exists()).toBe(false)

    const slotted = mount(PageHeader, { props: { title: '设备' }, slots: { subtitle: '<code>pk1</code>' } })
    expect(slotted.find('.subtitle code').text()).toBe('pk1')
  })
})
```

Create `conduit-ui/src/views/NotFoundView.spec.ts`:

```ts
/** NotFoundView 组件测试：未知地址显示「页面不存在」，「回到首页」跳转到根路径 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NotFoundView from './NotFoundView.vue'
import { createTestRouter, settle } from '@/test/viewTestUtils'

describe('NotFoundView', () => {
  it('explains the page does not exist and goes home', async () => {
    const router = createTestRouter()
    await router.push('/no/such/page')
    await router.isReady()
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const wrapper = mount(NotFoundView, { global: { plugins: [router, ElementPlus] } })

    expect(router.currentRoute.value.name).toBe('not-found')
    expect(wrapper.text()).toContain('页面不存在')
    await wrapper.findAll('button').find((b) => b.text() === '回到首页')!.trigger('click')
    await settle()

    expect(push).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/composables/useBreadcrumb.spec.ts src/layouts/AppLayout.spec.ts src/components/PageHeader.spec.ts src/views/NotFoundView.spec.ts ; cd ..`
Expected: FAIL — `Failed to resolve import "./useBreadcrumb"`, `"@/router/meta"`, `"./PageHeader.vue"`, `"./NotFoundView.vue"`.

- [ ] **Step 3: Add the route meta and the test router**

Create `conduit-ui/src/router/meta.ts`:

```ts
/**
 * 路由元信息：页面名称（浏览器标题）、面包屑与侧栏菜单归属。
 * 真实路由表（router/index.ts）与测试用内存路由（test/viewTestUtils.ts）共用 ROUTE_META，保证两边一致。
 */
import type { RouteLocationRaw, RouteMeta } from 'vue-router'

/** 面包屑的一级；有 to 时可点击跳转 */
export interface BreadcrumbItem {
  label: string
  to?: RouteLocationRaw
}

declare module 'vue-router' {
  interface RouteMeta {
    /** 不需要登录即可访问（登录页） */
    public?: boolean
    /** 页面名称，用于浏览器标签标题「<页面名> · Conduit」 */
    title?: string
    /** 面包屑（详情页的对象名称不在这里，由 useBreadcrumb 追加为最后一级） */
    breadcrumb?: BreadcrumbItem[]
    /** 侧栏中高亮的菜单路径：列表页与详情页归同一个菜单 */
    menu?: string
  }
}

/** 各路由的元信息，键为路由名称 */
export const ROUTE_META = {
  login: { public: true, title: '登录' },
  products: { title: '产品', menu: '/products', breadcrumb: [{ label: '设备接入' }, { label: '产品' }] },
  'product-detail': {
    title: '产品详情',
    menu: '/products',
    breadcrumb: [{ label: '设备接入' }, { label: '产品', to: { name: 'products' } }],
  },
  devices: { title: '设备', menu: '/devices', breadcrumb: [{ label: '设备接入' }, { label: '设备' }] },
  'device-detail': {
    title: '设备详情',
    menu: '/devices',
    breadcrumb: [{ label: '设备接入' }, { label: '设备', to: { name: 'devices' } }],
  },
  'not-found': { title: '页面不存在', breadcrumb: [{ label: '页面不存在' }] },
} satisfies Record<string, RouteMeta>
```

Replace `conduit-ui/src/test/viewTestUtils.ts` with:

```ts
/** 页面组件测试的公共工具：与真实路由同名、同 meta 的内存路由，以及等待异步渲染完成。 */

import { flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import { ROUTE_META } from '@/router/meta'

const Empty = defineComponent({ template: '<div />' })

/** 与真实路由同名、同 meta 的内存路由，组件全部用空壳，只用来断言跳转、query、面包屑与菜单高亮 */
export function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: Empty, meta: ROUTE_META.login },
      { path: '/products', name: 'products', component: Empty, meta: ROUTE_META.products },
      {
        path: '/products/:productKey',
        name: 'product-detail',
        component: Empty,
        props: true,
        meta: ROUTE_META['product-detail'],
      },
      { path: '/devices', name: 'devices', component: Empty, meta: ROUTE_META.devices },
      {
        path: '/devices/:deviceKey',
        name: 'device-detail',
        component: Empty,
        props: true,
        meta: ROUTE_META['device-detail'],
      },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: Empty, meta: ROUTE_META['not-found'] },
    ],
  })
}

/** 等待挂载后的异步加载和渲染都完成 */
export async function settle() {
  await flushPromises()
  await flushPromises()
}
```

Replace `conduit-ui/src/router/index.ts` with:

```ts
/**
 * 路由表与登录守卫。除 meta.public 的登录页外，所有页面都挂在 AppLayout 下并要求已登录；
 * 每个路由带 meta（页面名称、面包屑、菜单归属，见 router/meta.ts）；未匹配的地址在布局内显示 404 页。
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ROUTE_META } from './meta'

/** 路由表；导出供测试使用。路由名称与 meta 与 test/viewTestUtils.ts 的内存路由保持一致。 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: ROUTE_META.login,
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/devices',
    children: [
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductsView.vue'),
        meta: ROUTE_META.products,
      },
      {
        path: 'products/:productKey',
        name: 'product-detail',
        component: () => import('@/views/ProductDetailView.vue'),
        props: true,
        meta: ROUTE_META['product-detail'],
      },
      {
        path: 'devices',
        name: 'devices',
        component: () => import('@/views/DevicesView.vue'),
        meta: ROUTE_META.devices,
      },
      {
        path: 'devices/:deviceKey',
        name: 'device-detail',
        component: () => import('@/views/DeviceDetailView.vue'),
        props: true,
        meta: ROUTE_META['device-detail'],
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: ROUTE_META['not-found'],
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 登录守卫：公开页直接放行；未登录时先尝试用 refresh Cookie 恢复会话（页面刷新场景），
 * 恢复失败再跳转登录页并带上 redirect，登录成功后回到原页面。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const auth = useAuthStore()
  if (auth.isAuthenticated || (await auth.restore())) {
    return true
  }
  return { name: 'login', query: { redirect: to.fullPath } }
})

export default router
```

In `conduit-ui/src/api/types.ts`, replace

```ts
export interface UserView {
  username: string
  role: string
}
```

with

```ts
/** 当前用户；tenantName 为所属租户名称（顶栏显示），租户不存在时为 null */
export interface UserView {
  username: string
  role: string
  tenantName: string | null
}
```

- [ ] **Step 4: Implement the breadcrumb and media-query composables**

Create `conduit-ui/src/composables/useBreadcrumb.ts`:

```ts
/**
 * 面包屑与浏览器标题：由当前路由的 meta.breadcrumb / meta.title 生成；详情页通过 useBreadcrumb().setName()
 * 追加对象名称（面包屑最后一级，并替换标题中的页面名）。对象名称在设置它的页面卸载时清除。
 */
import { computed, onBeforeUnmount, ref, type ComputedRef } from 'vue'
import { useRoute } from 'vue-router'
import type { BreadcrumbItem } from '@/router/meta'

export const APP_NAME = 'Conduit'

const objectName = ref<string | null>(null)

/**
 * 生成面包屑：meta 中的各级，再追加对象名称（有时）；最后一级是当前页，不可点击（去掉 to）。
 * @param base 路由 meta.breadcrumb；没有时视为空
 * @param name 详情页的对象名称；没有时为 null
 */
export function buildBreadcrumb(base: readonly BreadcrumbItem[] | undefined, name: string | null): BreadcrumbItem[] {
  const items: BreadcrumbItem[] = [...(base ?? [])]
  if (name) {
    items.push({ label: name })
  }
  return items.map((item, i) => (i === items.length - 1 ? { label: item.label } : item))
}

/**
 * 浏览器标签标题：「对象名称 · Conduit」，没有对象名称时「页面名 · Conduit」，都没有时只有 Conduit。
 */
export function documentTitle(pageTitle: string | undefined, name: string | null): string {
  const head = name || pageTitle
  return head ? `${head} · ${APP_NAME}` : APP_NAME
}

/**
 * 详情页使用：设置面包屑最后一级与标题中的对象名称。组件卸载时自动清除；卸载之后才到达的 setName（如慢请求）被忽略。
 */
export function useBreadcrumb(): { setName(name: string | null): void } {
  let alive = true
  onBeforeUnmount(() => {
    alive = false
    objectName.value = null
  })
  return {
    setName(name: string | null) {
      if (alive) {
        objectName.value = name
      }
    },
  }
}

/** 布局与根组件使用：当前路由的面包屑与浏览器标题 */
export function useCurrentPage(): { breadcrumb: ComputedRef<BreadcrumbItem[]>; title: ComputedRef<string> } {
  const route = useRoute()
  return {
    breadcrumb: computed(() => buildBreadcrumb(route.meta.breadcrumb, objectName.value)),
    title: computed(() => documentTitle(route.meta.title, objectName.value)),
  }
}
```

Create `conduit-ui/src/composables/useMediaQuery.ts`:

```ts
/** 响应式媒体查询：返回当前是否匹配，媒体条件变化时更新，组件卸载时移除监听；不支持 matchMedia 的环境恒为 false。 */
import { onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 在组件 setup 中调用。
 * @param query CSS 媒体查询，例如 '(max-width: 819px)'
 */
export function useMediaQuery(query: string): Readonly<Ref<boolean>> {
  const matches = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return matches
  }
  const mql = window.matchMedia(query)
  matches.value = mql.matches
  const onChange = (event: MediaQueryListEvent) => {
    matches.value = event.matches
  }
  mql.addEventListener('change', onChange)
  onBeforeUnmount(() => mql.removeEventListener('change', onChange))
  return matches
}
```

- [ ] **Step 5: Implement the menu, layout, page header, 404 and root component**

Create `conduit-ui/src/layouts/menu.ts`:

```ts
/** 侧栏菜单定义：分组、菜单项（路径、名称、图标），以及尚未实现、显示为禁用的项（disabledNote 为标注文字）。 */
import type { Component } from 'vue'
import { Box, Cpu, OfficeBuilding, User } from '@element-plus/icons-vue'

/** 菜单项；path 同时是 el-menu 的 index 与跳转地址 */
export interface MenuItem {
  path: string
  label: string
  icon: Component
  /** 有值时菜单项禁用，并显示这段标注（如「V0.2-C」） */
  disabledNote?: string
}

/** 菜单分组 */
export interface MenuGroup {
  title: string
  items: MenuItem[]
}

export const MENU_GROUPS: MenuGroup[] = [
  {
    title: '设备接入',
    items: [
      { path: '/products', label: '产品', icon: Box },
      { path: '/devices', label: '设备', icon: Cpu },
    ],
  },
  {
    title: '系统管理',
    items: [
      { path: '/users', label: '用户', icon: User, disabledNote: 'V0.2-C' },
      { path: '/tenants', label: '租户', icon: OfficeBuilding, disabledNote: 'V0.2-C' },
    ],
  },
]
```

Replace `conduit-ui/src/layouts/AppLayout.vue` with:

```vue
<script setup lang="ts">
/**
 * 登录后的整体布局。左侧侧栏：Logo、分组菜单（带图标，系统管理项为禁用的「V0.2-C」），按路由 meta.menu 高亮；
 * 可折叠（偏好保存，窗口宽度 < 820px 时强制折叠），浅色/深色两种风格（偏好）。
 * 顶栏：左侧全局面包屑（路由 meta + 详情页对象名称），右侧外观设置、租户名称、用户头像下拉（用户名、租户、退出登录）。
 * 页面渲染在 router-view。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Brush, DataLine, Expand, Fold } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useCurrentPage } from '@/composables/useBreadcrumb'
import { useMediaQuery } from '@/composables/useMediaQuery'
import AppearancePanel from '@/components/AppearancePanel.vue'
import { MENU_GROUPS } from './menu'

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const { breadcrumb } = useCurrentPage()
const narrow = useMediaQuery('(max-width: 819px)')

/** 是否折叠：窄屏强制折叠，否则按偏好 */
const collapsed = computed(() => narrow.value || ui.sidebarCollapsed)
/** 高亮的菜单：列表页与详情页通过 meta.menu 归到同一项 */
const activeMenu = computed(() => route.meta.menu ?? '')
/** 头像文字：用户名首字（大写） */
const avatarText = computed(() => (auth.user?.username ?? '?').slice(0, 1).toUpperCase())

/** 退出登录：无论登出请求是否成功，都回到登录页。 */
async function logout() {
  try {
    await auth.logout()
  } catch {
    // 登出请求失败也要回到登录页
  } finally {
    await router.replace({ name: 'login' })
  }
}

/** 用户下拉菜单的命令：目前只有退出登录 */
function onCommand(command: string) {
  if (command === 'logout') {
    void logout()
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? 'var(--cd-sidebar-collapsed-width)' : 'var(--cd-sidebar-width)'" :class="['sidebar', `cd-sidebar--${ui.sidebarStyle}`]">
      <div class="brand">
        <el-icon :size="22" class="brand-icon"><DataLine /></el-icon>
        <span v-if="!collapsed" class="brand-name">Conduit</span>
      </div>
      <el-menu :default-active="activeMenu" :collapse="collapsed" :collapse-transition="false" router class="menu">
        <el-menu-item-group v-for="group in MENU_GROUPS" :key="group.title" :title="group.title">
          <el-menu-item
            v-for="item in group.items"
            :key="item.path"
            :index="item.path"
            :disabled="Boolean(item.disabledNote)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>
              <span>{{ item.label }}</span>
              <el-tag v-if="item.disabledNote" size="small" type="info" class="note">{{ item.disabledNote }}</el-tag>
            </template>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
      <div
        v-if="!narrow"
        class="collapse-toggle"
        role="button"
        tabindex="0"
        :title="collapsed ? '展开菜单' : '收起菜单'"
        @click="ui.toggleSidebar()"
        @keydown.enter="ui.toggleSidebar()"
      >
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起菜单</span>
      </div>
    </el-aside>

    <el-container class="main">
      <el-header class="header">
        <el-breadcrumb separator="/" class="breadcrumb">
          <el-breadcrumb-item v-for="(item, i) in breadcrumb" :key="i" :to="item.to">{{ item.label }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="header-right">
          <el-popover trigger="click" placement="bottom-end" :width="300">
            <template #reference>
              <el-button circle :icon="Brush" title="外观设置" aria-label="外观设置" />
            </template>
            <AppearancePanel />
          </el-popover>
          <el-tag v-if="auth.user?.tenantName" type="info" class="tenant">{{ auth.user.tenantName }}</el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="user" role="button" tabindex="0">
              <el-avatar :size="28">{{ avatarText }}</el-avatar>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.user?.username }}</el-dropdown-item>
                <el-dropdown-item disabled>租户：{{ auth.user?.tenantName ?? '—' }}</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}
.sidebar {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--cd-sidebar-bg);
  border-right: 1px solid var(--cd-sidebar-border);
  transition: width 0.2s;
}
.cd-sidebar--dark {
  --cd-sidebar-bg: var(--cd-sidebar-dark-bg);
  --cd-sidebar-text: var(--cd-sidebar-dark-text);
  --cd-sidebar-muted: var(--cd-sidebar-dark-muted);
  --cd-sidebar-hover: var(--cd-sidebar-dark-hover);
  --cd-sidebar-active-bg: var(--cd-sidebar-dark-active-bg);
  --cd-sidebar-active-text: var(--cd-sidebar-dark-active-text);
  --cd-sidebar-border: var(--cd-sidebar-dark-border);
}
.cd-sidebar--light {
  --cd-sidebar-bg: var(--cd-sidebar-light-bg);
  --cd-sidebar-text: var(--cd-sidebar-light-text);
  --cd-sidebar-muted: var(--cd-sidebar-light-muted);
  --cd-sidebar-hover: var(--cd-sidebar-light-hover);
  --cd-sidebar-active-bg: var(--cd-sidebar-light-active-bg);
  --cd-sidebar-active-text: var(--cd-sidebar-light-active-text);
  --cd-sidebar-border: var(--cd-sidebar-light-border);
}
.brand {
  height: var(--cd-header-height);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  padding: 0 var(--cd-space-5);
  color: var(--cd-sidebar-text);
  font-size: 18px;
  font-weight: 600;
}
.brand-icon {
  color: var(--el-color-primary);
}
.menu {
  flex: 1;
  border-right: none;
  overflow-y: auto;
  --el-menu-bg-color: var(--cd-sidebar-bg);
  --el-menu-text-color: var(--cd-sidebar-text);
  --el-menu-hover-bg-color: var(--cd-sidebar-hover);
  --el-menu-active-color: var(--cd-sidebar-active-text);
  --el-menu-item-height: 44px;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--cd-sidebar-active-bg);
  color: var(--cd-sidebar-active-text);
}
.menu :deep(.el-menu-item-group__title) {
  padding-top: var(--cd-space-3);
  color: var(--cd-sidebar-muted);
  font-size: 12px;
}
.menu.el-menu--collapse :deep(.el-menu-item-group__title) {
  display: none;
}
.note {
  margin-left: auto;
}
.collapse-toggle {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  padding: var(--cd-space-3) var(--cd-space-5);
  color: var(--cd-sidebar-muted);
  border-top: 1px solid var(--cd-sidebar-border);
  cursor: pointer;
}
.collapse-toggle:hover {
  color: var(--cd-sidebar-text);
  background: var(--cd-sidebar-hover);
}
.main {
  min-width: 0;
}
.header {
  height: var(--cd-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cd-space-3);
  background: var(--cd-panel-bg);
  border-bottom: 1px solid var(--el-border-color-light);
}
.breadcrumb {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.header-right {
  display: flex;
  align-items: center;
  gap: var(--cd-space-3);
  flex-shrink: 0;
}
.user {
  display: flex;
  align-items: center;
  gap: var(--cd-space-1);
  color: var(--el-text-color-regular);
  cursor: pointer;
}
.content {
  min-width: 0;
  background: var(--cd-page-bg);
}
@media (max-width: 819px) {
  .tenant {
    display: none;
  }
  .content {
    padding: var(--cd-space-3);
  }
}
</style>
```

Create `conduit-ui/src/components/PageHeader.vue`:

```vue
<script setup lang="ts">
/** 统一页头：标题、可选副标题（一句说明，可用 subtitle 插槽放富内容）与右侧操作按钮插槽 actions；窄屏时操作区换行。 */
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="text">
      <h2 class="title">{{ title }}</h2>
      <p v-if="subtitle || $slots.subtitle" class="subtitle">
        <slot name="subtitle">{{ subtitle }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--cd-space-3);
  margin-bottom: var(--cd-space-4);
}
.title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.subtitle {
  margin: var(--cd-space-1) 0 0;
  color: var(--cd-text-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-2);
}
</style>
```

Create `conduit-ui/src/views/NotFoundView.vue`:

```vue
<script setup lang="ts">
/** 404 页：未匹配的地址显示「页面不存在」与可能的原因，提供「回到首页」（跳到根路径，由路由重定向到默认页）。 */
import { useRouter } from 'vue-router'

const router = useRouter()

/** 回到首页 */
function goHome() {
  void router.push('/')
}
</script>

<template>
  <el-result icon="warning" title="页面不存在" sub-title="地址可能输错了，或者页面已被移除。">
    <template #extra>
      <el-button type="primary" @click="goHome">回到首页</el-button>
    </template>
  </el-result>
</template>
```

Replace `conduit-ui/src/App.vue` with:

```vue
<script setup lang="ts">
/** 应用根组件：渲染路由出口，并让浏览器标签标题跟随当前页面（「页面名或对象名称 · Conduit」）。 */
import { watchEffect } from 'vue'
import { useCurrentPage } from '@/composables/useBreadcrumb'

const { title } = useCurrentPage()

watchEffect(() => {
  document.title = title.value
})
</script>

<template>
  <router-view />
</template>
```

- [ ] **Step 6: Run the shell specs to verify they pass**

Run: `cd conduit-ui && npx vitest run src/composables/useBreadcrumb.spec.ts src/layouts/AppLayout.spec.ts src/components/PageHeader.spec.ts src/views/NotFoundView.spec.ts ; cd ..`
Expected: all PASS.

- [ ] **Step 7: Run all frontend tests, the type check and the build**

Run: `cd conduit-ui && npm test && npm run build ; cd ..`
Expected: all specs PASS (the existing view specs keep working with the new test router), type check clean, build succeeds.

- [ ] **Step 8: Manual check against a dev server**

Run the UI against a reachable server (README 「开发」: `VITE_API_TARGET=http://<docker-host>:8091 npm run dev` in `conduit-ui`). Log in. Expected: dark sidebar with 设备接入 / 系统管理 groups and icons, 用户/租户 greyed out with 「V0.2-C」; 「收起菜单」 collapses to icons with hover names and survives a reload; the brush button opens the appearance panel and 深色 turns the whole page dark; the breadcrumb reads 「设备接入 / 设备」 on the device list; the tab title reads 「设备 · Conduit」; `/nope` shows the 404 page inside the frame. Narrow the window below 820px: the sidebar collapses and the toggle disappears.

- [ ] **Step 9: Commit**

```bash
git add conduit-ui/src/router conduit-ui/src/composables/useBreadcrumb.ts conduit-ui/src/composables/useBreadcrumb.spec.ts \
  conduit-ui/src/composables/useMediaQuery.ts conduit-ui/src/layouts conduit-ui/src/components/PageHeader.vue \
  conduit-ui/src/components/PageHeader.spec.ts conduit-ui/src/views/NotFoundView.vue conduit-ui/src/views/NotFoundView.spec.ts \
  conduit-ui/src/App.vue conduit-ui/src/api/types.ts conduit-ui/src/test/viewTestUtils.ts
git commit -m "feat(ui): grouped collapsible sidebar, breadcrumb top bar, page header and 404"
```

---
## Task 5: List kit and the migrated product and device lists

**Files:**
- Create: `conduit-ui/src/composables/useTable.ts`
- Create: `conduit-ui/src/components/FilterBar.vue`, `conduit-ui/src/components/OnlineDot.vue`, `conduit-ui/src/components/RelativeTime.vue`
- Create: `conduit-ui/src/utils/relativeTime.ts`
- Modify: `conduit-ui/src/utils/format.ts` (add `formatCount`)
- Modify: `conduit-ui/src/api/types.ts` (`ProductView`)
- Modify: `conduit-ui/src/views/ProductsView.vue`, `conduit-ui/src/views/DevicesView.vue`, `conduit-ui/src/views/ProductDetailView.vue` (full replacements below)
- Test: `conduit-ui/src/composables/useTable.spec.ts`, `conduit-ui/src/components/FilterBar.spec.ts`, `conduit-ui/src/components/OnlineDot.spec.ts`, `conduit-ui/src/components/RelativeTime.spec.ts`, `conduit-ui/src/utils/relativeTime.spec.ts`, `conduit-ui/src/views/DevicesView.spec.ts` (new)
- Test: `conduit-ui/src/utils/format.spec.ts` (new `describe`), `conduit-ui/src/views/ProductsView.spec.ts` and `conduit-ui/src/views/ProductDetailView.spec.ts` (full replacements below), `conduit-ui/src/views/DeviceDetailView.spec.ts` (fixture only)

**Interfaces:**
- Consumes: Task 1 JSON `ProductView.deviceCount/onlineCount`; Task 4 `PageHeader`, `useBreadcrumb().setName`, `createTestRouter`/`settle`; existing `productApi`, `deviceApi`, `codecApi`, `thingModelApi`, `problemMessage`, `clampPage`/`pageSlice`/`DEFAULT_PAGE_SIZE`/`PAGE_SIZES`, `createRequestSeq`, `tabFromQuery`/`tabQuery`, `copyText`, `downloadJson`, `formatDateTime`, `codecLabel`, `dataTypeLabel`, `formatRange`, `SecretDialog`, `PropertyDialog`, `ThingModelImportDialog`.
- Produces (used by Tasks 6–7):
  - `composables/useTable.ts`: `type PageFetcher<T> = (page: number, size: number) => Promise<PageView<T>>`; `interface TableState<T> { items; total; page; size; loading; loaded: Ref<…>; load(); search(); onSizeChange(); onPageChange() }`; `useTable<T>(fetch: PageFetcher<T>): TableState<T>`.
  - `<FilterBar @search @reset>` with a default slot for the filter controls.
  - `<OnlineDot :online="boolean" />`, `<RelativeTime :value="string | number | null | undefined" />`.
  - `utils/relativeTime.ts`: `relativeTime(value: string | number | null | undefined, now: number): string`.
  - `utils/format.ts`: `formatCount(value: number | null | undefined): string` (`null`/`undefined` → 「—」).
  - `api/types.ts`: `ProductView.deviceCount: number`, `ProductView.onlineCount: number | null`.

**Decisions recorded here:**
- `useTable` corrects an out-of-range page itself: when a response for page N is empty and `clampPage(N, total, size) < N`, it moves to the last page and loads once more. This one rule covers delete-last-row, import and page-size changes, so views just call `load()` after a mutation. Views bind `@current-change="onPageChange"` and `@size-change="onSizeChange"`; `useTable` does not watch the refs, so one user action never triggers two requests.
- The product detail's property table also uses `useTable`, with a fetcher that slices the locally loaded array (`pageSlice`), so local and server paging share the clamp and sequence logic.
- The device list shows 启用/禁用 as an `el-tag` (spec §3) and moves the switch into the 「操作」 column as a 「禁用」/「启用」 button.
- The product list gets no filter bar: `GET /api/v1/products` has no keyword parameter and the spec's backend scope is limited to two read-only additions. `FilterBar` is used on the device list (product + keyword). The product detail's 设备 tab keeps product-scoped paging only.
- `RelativeTime` thresholds: under 1 minute (and future times from clock skew) 「刚刚」; under 1 hour 「N 分钟前」; under 24 hours 「N 小时前」; under 30 days 「N 天前」; older → the date `yyyy-MM-dd`. Each instance runs its own 60 s interval; the device list has at most 100 rows per page.
- Skeleton vs mask: list pages render `el-skeleton` until `loaded` turns true (after the first response or failure), then the table with `v-loading` for later loads. Detail-page tabs use only `v-loading`.

- [ ] **Step 1: Write the failing kit specs**

Create `conduit-ui/src/composables/useTable.spec.ts`:

```ts
/** useTable 的单元测试：默认第 1 页 10 条、筛选与改条数回到第 1 页、过期响应丢弃、越界页码修正、失败提示与首次加载标记 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import type { PageView } from '@/api/types'
import { useTable } from './useTable'

function pageOf(items: string[], total: number, page = 1, size = 10): PageView<string> {
  return { items, total, page, size }
}

/** 可手动完成的 Promise，用来制造乱序响应 */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

describe('useTable', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads page 1 with 10 rows and marks the first load as done', async () => {
    const fetch = vi.fn().mockResolvedValue(pageOf(['a', 'b'], 2))
    const t = useTable<string>(fetch)
    expect(t.loaded.value).toBe(false)

    await t.load()

    expect(fetch).toHaveBeenCalledWith(1, 10)
    expect(t.items.value).toEqual(['a', 'b'])
    expect(t.total.value).toBe(2)
    expect(t.loading.value).toBe(false)
    expect(t.loaded.value).toBe(true)
  })

  it('goes back to page 1 on search and on a page-size change, and loads the chosen page', async () => {
    const fetch = vi.fn().mockResolvedValue(pageOf(['x'], 500))
    const t = useTable<string>(fetch)

    t.page.value = 3
    await t.search()
    expect(fetch).toHaveBeenLastCalledWith(1, 10)

    t.page.value = 2
    t.size.value = 50
    await t.onSizeChange()
    expect(fetch).toHaveBeenLastCalledWith(1, 50)

    t.page.value = 4
    await t.onPageChange()
    expect(fetch).toHaveBeenLastCalledWith(4, 50)
  })

  it('drops a response that arrives after a newer one', async () => {
    const slow = deferred<PageView<string>>()
    const fast = deferred<PageView<string>>()
    const fetch = vi.fn().mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)
    const t = useTable<string>(fetch)

    const first = t.load()
    const second = t.load()
    fast.resolve(pageOf(['new'], 1))
    await second
    slow.resolve(pageOf(['old'], 1))
    await first

    expect(t.items.value).toEqual(['new'])
    expect(t.loading.value).toBe(false)
  })

  it('moves back to the last page when the current page no longer exists', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(pageOf([], 15, 3)).mockResolvedValueOnce(pageOf(['k'], 15, 2))
    const t = useTable<string>(fetch)
    t.page.value = 3

    await t.load()

    expect(fetch).toHaveBeenNthCalledWith(1, 3, 10)
    expect(fetch).toHaveBeenNthCalledWith(2, 2, 10)
    expect(t.page.value).toBe(2)
    expect(t.items.value).toEqual(['k'])
  })

  it('shows the error of the latest request and keeps the previous rows', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    const fetch = vi.fn().mockResolvedValueOnce(pageOf(['a'], 1)).mockRejectedValueOnce(new Error('服务不可用'))
    const t = useTable<string>(fetch)

    await t.load()
    await t.load()

    expect(error).toHaveBeenCalledWith('服务不可用')
    expect(t.items.value).toEqual(['a'])
    expect(t.loaded.value).toBe(true)
    expect(t.loading.value).toBe(false)
  })
})
```

Create `conduit-ui/src/utils/relativeTime.spec.ts`:

```ts
/** relativeTime 的单元测试：刚刚 / 分钟 / 小时 / 天 / 超过 30 天显示日期 / 空值与无效值 */
import { describe, expect, it } from 'vitest'
import { relativeTime } from './relativeTime'

const NOW = new Date(2026, 9, 15, 8, 0, 0).getTime()
const MIN = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000

describe('relativeTime', () => {
  it.each([
    [NOW - 10_000, '刚刚'],
    [NOW + 5_000, '刚刚'],
    [NOW - 3 * MIN, '3 分钟前'],
    [NOW - 59 * MIN, '59 分钟前'],
    [NOW - 2 * HOUR, '2 小时前'],
    [NOW - 3 * DAY, '3 天前'],
    [NOW - 29 * DAY, '29 天前'],
  ])('formats %s', (value, expected) => {
    expect(relativeTime(value, NOW)).toBe(expected)
  })

  it('shows the date for anything older than 30 days', () => {
    expect(relativeTime(new Date(2026, 7, 1, 9, 30).getTime(), NOW)).toBe('2026-08-01')
  })

  it('accepts ISO strings', () => {
    expect(relativeTime(new Date(NOW - 5 * MIN).toISOString(), NOW)).toBe('5 分钟前')
  })

  it.each([null, undefined, '', 'not a date'])('shows — for %s', (value) => {
    expect(relativeTime(value, NOW)).toBe('—')
  })
})
```

Create `conduit-ui/src/components/RelativeTime.spec.ts`:

```ts
/** RelativeTime 组件测试：相对时间文案、悬停显示完整时间、每分钟自动更新、空值 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import RelativeTime from './RelativeTime.vue'
import { formatDateTime } from '@/utils/format'

const NOW = new Date(2026, 9, 15, 8, 0, 0).getTime()

describe('RelativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the relative text, the full time on hover and updates every minute', async () => {
    const value = NOW - 3 * 60_000
    const wrapper = mount(RelativeTime, { props: { value } })

    expect(wrapper.text()).toBe('3 分钟前')
    expect(wrapper.attributes('title')).toBe(formatDateTime(value))

    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.text()).toBe('4 分钟前')
    wrapper.unmount()
  })

  it('shows — without a title for an empty value', () => {
    const wrapper = mount(RelativeTime, { props: { value: null } })

    expect(wrapper.text()).toBe('—')
    expect(wrapper.attributes('title')).toBeUndefined()
    wrapper.unmount()
  })
})
```

Create `conduit-ui/src/components/OnlineDot.spec.ts`:

```ts
/** OnlineDot 组件测试：在线绿点「在线」、离线灰点「离线」 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import OnlineDot from './OnlineDot.vue'

describe('OnlineDot', () => {
  it('renders the online and offline states', () => {
    const on = mount(OnlineDot, { props: { online: true } })
    const off = mount(OnlineDot, { props: { online: false } })

    expect(on.text()).toBe('在线')
    expect(on.classes()).toContain('is-online')
    expect(off.text()).toBe('离线')
    expect(off.classes()).not.toContain('is-online')
  })
})
```

Create `conduit-ui/src/components/FilterBar.spec.ts`:

```ts
/** FilterBar 组件测试：默认插槽放筛选控件；提交（回车或「查询」）发出 search，「重置」发出 reset */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FilterBar from './FilterBar.vue'

describe('FilterBar', () => {
  it('emits search on submit and reset from the reset button', async () => {
    const wrapper = mount(FilterBar, {
      global: { plugins: [ElementPlus] },
      slots: { default: '<input class="keyword" />' },
    })

    expect(wrapper.find('input.keyword').exists()).toBe(true)
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('search')).toHaveLength(1)

    await wrapper.findAll('button').find((b) => b.text() === '重置')!.trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })
})
```

In `conduit-ui/src/utils/format.spec.ts`, add the header comment `/** format 的单元测试：日期时间格式化与计数显示 */` as the first line, change the import line to `import { formatCount, formatDateTime } from './format'` and append:

```ts
describe('formatCount', () => {
  it('shows numbers as they are and — for unknown values', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(100)).toBe('100')
    expect(formatCount(null)).toBe('—')
    expect(formatCount(undefined)).toBe('—')
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/composables/useTable.spec.ts src/utils/relativeTime.spec.ts src/components/RelativeTime.spec.ts src/components/OnlineDot.spec.ts src/components/FilterBar.spec.ts src/utils/format.spec.ts ; cd ..`
Expected: FAIL — unresolved imports `./useTable`, `./relativeTime`, `./RelativeTime.vue`, `./OnlineDot.vue`, `./FilterBar.vue`; `formatCount` is not exported.

- [ ] **Step 3: Implement the kit**

Create `conduit-ui/src/composables/useTable.ts`:

```ts
/**
 * 分页表格的通用状态（服务端分页与前端分页都适用）：页码与每页条数（默认 10 条）、加载状态、首次加载标记、
 * 请求序号（只有最后发出的请求能写入结果）与越界页码修正（删除等让总数变少、当前页已不存在时退到最后一页并重新加载）。
 * 取代各页面手写的 page/size/loading/requestSeq/clampPage 代码。
 */
import { ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { problemMessage } from '@/api/errors'
import type { PageView } from '@/api/types'
import { clampPage, DEFAULT_PAGE_SIZE } from '@/utils/pagination'
import { createRequestSeq } from '@/utils/requestSeq'

/** 取一页数据；page 从 1 开始 */
export type PageFetcher<T> = (page: number, size: number) => Promise<PageView<T>>

/** useTable 返回的状态与操作；页面解构后在模板中使用（解构出的 ref 才会在模板中自动解包） */
export interface TableState<T> {
  items: Ref<T[]>
  total: Ref<number>
  page: Ref<number>
  size: Ref<number>
  /** 正在加载：表格的 loading 遮罩 */
  loading: Ref<boolean>
  /** 首次加载已结束（成功或失败）；之前显示骨架屏 */
  loaded: Ref<boolean>
  /** 按当前页码与条数加载 */
  load(): Promise<void>
  /** 回到第 1 页加载（筛选条件变化时） */
  search(): Promise<void>
  /** 每页条数变化：回到第 1 页加载 */
  onSizeChange(): Promise<void>
  /** 页码变化：加载该页 */
  onPageChange(): Promise<void>
}

/**
 * 创建分页表格状态。不会自动加载，由页面在挂载或切换标签时调用 load()。
 * @param fetch 取一页数据的函数；抛错时提示接口错误并保留上一次的数据
 */
export function useTable<T>(fetch: PageFetcher<T>): TableState<T> {
  const items = ref([]) as Ref<T[]>
  const total = ref(0)
  const page = ref(1)
  const size = ref(DEFAULT_PAGE_SIZE)
  const loading = ref(false)
  const loaded = ref(false)
  const seq = createRequestSeq()

  /**
   * 加载当前页。先发后到的旧响应（及其错误提示、loading 复位）被丢弃；
   * 当前页为空且超出最后一页时，退到最后一页再加载一次。
   */
  async function load(): Promise<void> {
    const id = seq.next()
    loading.value = true
    try {
      const res = await fetch(page.value, size.value)
      if (!seq.isLatest(id)) return
      const last = clampPage(page.value, res.total, size.value)
      if (res.items.length === 0 && last < page.value) {
        page.value = last
        await load()
        return
      }
      items.value = res.items
      total.value = res.total
    } catch (e) {
      if (seq.isLatest(id)) ElMessage.error(problemMessage(e))
    } finally {
      if (seq.isLatest(id)) {
        loading.value = false
        loaded.value = true
      }
    }
  }

  /** 回到第 1 页加载 */
  function search(): Promise<void> {
    page.value = 1
    return load()
  }

  return { items, total, page, size, loading, loaded, load, search, onSizeChange: search, onPageChange: load }
}
```

Create `conduit-ui/src/utils/relativeTime.ts`:

```ts
/** 相对时间文案：「刚刚」「N 分钟前」「N 小时前」「N 天前」，超过 30 天显示日期。 */
import { formatDateTime } from './format'

const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

/**
 * 把时间转成相对 now 的文案。
 * @param value ISO 字符串或毫秒时间戳；空值或无法解析时返回「—」
 * @param now 当前时间（毫秒）
 * @returns 不到 1 分钟（含时钟偏差导致的未来时间）为「刚刚」；不到 1 小时为「N 分钟前」；不到 1 天为「N 小时前」；
 *          不到 30 天为「N 天前」；更早为 yyyy-MM-dd
 */
export function relativeTime(value: string | number | null | undefined, now: number): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const t = new Date(value).getTime()
  if (Number.isNaN(t)) {
    return '—'
  }
  const diff = now - t
  if (diff < MIN) {
    return '刚刚'
  }
  if (diff < HOUR) {
    return `${Math.floor(diff / MIN)} 分钟前`
  }
  if (diff < DAY) {
    return `${Math.floor(diff / HOUR)} 小时前`
  }
  if (diff < 30 * DAY) {
    return `${Math.floor(diff / DAY)} 天前`
  }
  return formatDateTime(t).slice(0, 10)
}
```

In `conduit-ui/src/utils/format.ts`, change the header comment to `/** 显示格式化：日期时间与计数。 */` and append:

```ts
/** 计数显示：null / undefined（如 Redis 不可用导致在线数未知）显示「—」，否则原样显示 */
export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : String(value)
}
```

Create `conduit-ui/src/components/RelativeTime.vue`:

```vue
<script setup lang="ts">
/** 相对时间：显示「3 分钟前」，悬停（title）显示完整时间，每分钟自动更新；空值显示「—」且没有悬停提示。 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatDateTime } from '@/utils/format'
import { relativeTime } from '@/utils/relativeTime'

const props = defineProps<{ value: string | number | null | undefined }>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined

const text = computed(() => relativeTime(props.value, now.value))
const full = computed(() => (props.value === null || props.value === undefined || props.value === '' ? undefined : formatDateTime(props.value)))

onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now()
  }, 60_000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <span class="relative-time" :title="full">{{ text }}</span>
</template>
```

Create `conduit-ui/src/components/OnlineDot.vue`:

```vue
<script setup lang="ts">
/** 在线状态：在线为绿点 +「在线」，离线为灰点 +「离线」。 */
defineProps<{ online: boolean }>()
</script>

<template>
  <span class="online-dot" :class="{ 'is-online': online }">
    <i class="dot" aria-hidden="true" />{{ online ? '在线' : '离线' }}
  </span>
</template>

<style scoped>
.online-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--cd-text-muted);
}
.online-dot.is-online {
  color: var(--el-text-color-regular);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--cd-offline);
}
.is-online .dot {
  background: var(--cd-online);
}
</style>
```

Create `conduit-ui/src/components/FilterBar.vue`:

```vue
<script setup lang="ts">
/** 列表筛选栏：一行放筛选控件（默认插槽）与「查询」「重置」按钮，窄屏自动换行；在输入框中回车即查询（表单提交）。 */
import { RefreshLeft, Search } from '@element-plus/icons-vue'

const emit = defineEmits<{ search: []; reset: [] }>()
</script>

<template>
  <el-form inline class="filter-bar" @submit.prevent="emit('search')">
    <slot />
    <el-form-item class="buttons">
      <el-button type="primary" native-type="submit" :icon="Search">查询</el-button>
      <el-button :icon="RefreshLeft" @click="emit('reset')">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  row-gap: var(--cd-space-2);
  margin-bottom: var(--cd-space-3);
}
.filter-bar :deep(.el-form-item) {
  margin-bottom: 0;
}
</style>
```

- [ ] **Step 4: Run the kit specs to verify they pass**

Run: `cd conduit-ui && npx vitest run src/composables/useTable.spec.ts src/utils/relativeTime.spec.ts src/components/RelativeTime.spec.ts src/components/OnlineDot.spec.ts src/components/FilterBar.spec.ts src/utils/format.spec.ts ; cd ..`
Expected: all PASS.

- [ ] **Step 5: Write the failing view specs**

In `conduit-ui/src/api/types.ts`, replace the `ProductView` interface with:

```ts
/** 产品；deviceCount 为设备数，onlineCount 为在线设备数（服务端 Redis 不可用时为 null，显示「—」） */
export interface ProductView {
  productKey: string
  name: string
  description: string | null
  codec: string
  propertyCount: number
  deviceCount: number
  onlineCount: number | null
  createdAt: string
}
```

In `conduit-ui/src/views/DeviceDetailView.spec.ts`, in the `vi.mocked(productApi.get).mockResolvedValue({ … })` fixture, add `deviceCount: 100,` and `onlineCount: 100,` after `propertyCount: 8,` (the file is replaced in Task 6; this keeps the type check green now).

Replace `conduit-ui/src/views/ProductsView.spec.ts` with:

```ts
/** ProductsView 组件测试：首屏请求第 1 页 10 条、「设备（在线/总数）」列、空状态引导新建、行内按钮跳转到详情的标签 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ProductsView from './ProductsView.vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import type { ProductView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
vi.mock('@/api/codecs', () => ({ codecApi: { list: vi.fn() } }))

function product(patch: Partial<ProductView> = {}): ProductView {
  return {
    productKey: 'pk1',
    name: '三相电表（演示）',
    description: null,
    codec: 'standard-json',
    propertyCount: 7,
    deviceCount: 3,
    onlineCount: 2,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

describe('ProductsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(productApi.list).mockResolvedValue({
      items: [product(), product({ productKey: 'pk2', name: '水表（演示）', deviceCount: 5, onlineCount: null })],
      total: 2,
      page: 1,
      size: 10,
    })
    vi.mocked(codecApi.list).mockResolvedValue([])
  })

  async function mountView() {
    const router = createTestRouter()
    await router.push('/products')
    await router.isReady()
    const wrapper = mount(ProductsView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body })
    await settle()
    return { wrapper, router }
  }

  it('requests the first page with 10 rows', async () => {
    const { wrapper } = await mountView()

    expect(productApi.list).toHaveBeenCalledWith(1, 10)
    wrapper.unmount()
  })

  it('shows online / total devices, with — when the online count is unknown', async () => {
    const { wrapper } = await mountView()

    const links = wrapper.findAll('.device-count')
    expect(links.map((l) => l.text())).toEqual(['2 / 3', '— / 5'])
    wrapper.unmount()
  })

  it('opens the devices tab of the product from the device count', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.device-count').trigger('click')
    await settle()

    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.query).toEqual({ tab: 'devices' })
    wrapper.unmount()
  })

  it('guides to create the first product when the list is empty', async () => {
    vi.mocked(productApi.list).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
    const { wrapper } = await mountView()

    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('还没有产品')
    await empty.findAll('button').find((b) => b.text() === '新建产品')!.trigger('click')

    expect((wrapper.vm as unknown as { dialogVisible: boolean }).dialogVisible).toBe(true)
    wrapper.unmount()
  })

  it('opens the thing-model tab of the product from the 物模型 button', async () => {
    const { wrapper, router } = await mountView()

    const button = wrapper.findAll('button').find((b) => b.text() === '物模型')
    expect(button).toBeDefined()
    await button!.trigger('click')
    await settle()

    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.params.productKey).toBe('pk1')
    expect(router.currentRoute.value.query).toEqual({ tab: 'model' })
    wrapper.unmount()
  })

  it('opens the basic tab from the 详情 button', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.findAll('button').find((b) => b.text() === '详情')!.trigger('click')
    await settle()

    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.query).toEqual({})
    wrapper.unmount()
  })
})
```

Create `conduit-ui/src/views/DevicesView.spec.ts`:

```ts
/** DevicesView 组件测试：首屏第 1 页 10 条、筛选栏查询与重置、在线圆点与启用标签、操作列启用/禁用 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DevicesView from './DevicesView.vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import type { DeviceView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))

function device(patch: Partial<DeviceView> = {}): DeviceView {
  return {
    deviceKey: 'dk1',
    name: '1号楼配电室 电表01',
    productKey: 'pk1',
    status: 'ENABLED',
    online: true,
    lastReportAt: Date.now() - 120_000,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

async function mountView() {
  const router = createTestRouter()
  await router.push('/devices')
  await router.isReady()
  const wrapper = mount(DevicesView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

describe('DevicesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(productApi.list).mockResolvedValue({
      items: [
        {
          productKey: 'pk1',
          name: '三相电表（演示）',
          description: null,
          codec: 'standard-json',
          propertyCount: 8,
          deviceCount: 1,
          onlineCount: 1,
          createdAt: '2026-10-01T00:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 100,
    })
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [device()], total: 1, page: 1, size: 10 })
  })

  it('loads the first page with 10 rows and no filters', async () => {
    const { wrapper } = await mountView()

    expect(deviceApi.list).toHaveBeenCalledWith({ productKey: undefined, keyword: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('searches with the keyword and resets the filters', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('.filter-bar .keyword input').setValue(' 电表01 ')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: '电表01', page: 1, size: 10 })

    await wrapper.findAll('.filter-bar button').find((b) => b.text() === '重置')!.trigger('click')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('shows the online dot, the enabled tag, the product name and the relative report time', async () => {
    const { wrapper } = await mountView()

    const text = wrapper.find('.el-table').text()
    expect(text).toContain('在线')
    expect(text).toContain('启用')
    expect(text).toContain('三相电表（演示）')
    expect(text).toContain('2 分钟前')
    wrapper.unmount()
  })

  it('disables a device from the action column', async () => {
    vi.mocked(deviceApi.update).mockResolvedValue(device({ status: 'DISABLED' }))
    const { wrapper } = await mountView()

    await wrapper.findAll('.el-table button').find((b) => b.text() === '禁用')!.trigger('click')
    await settle()

    expect(deviceApi.update).toHaveBeenCalledWith('dk1', '1号楼配电室 电表01', 'DISABLED')
    expect(wrapper.find('.el-table').text()).toContain('禁用')
    expect(wrapper.findAll('.el-table button').some((b) => b.text() === '启用')).toBe(true)
    wrapper.unmount()
  })
})
```

Replace `conduit-ui/src/views/ProductDetailView.spec.ts` with:

```ts
/**
 * ProductDetailView 组件测试：标签与 ?tab= 同步（router.replace）、页头显示产品 Key 与设备计数、
 * 属性变化后只刷新计数不覆盖未保存的基本信息、属性前端分页（每页 10 条）与删除后页码修正、设备标签的在线圆点。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElPagination, ElTable } from 'element-plus'
import ProductDetailView from './ProductDetailView.vue'
import PropertyDialog from '@/components/PropertyDialog.vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { thingModelApi } from '@/api/thingModel'
import { deviceApi } from '@/api/devices'
import type { ProductView, PropertyView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
vi.mock('@/api/codecs', () => ({ codecApi: { list: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    exportModel: vi.fn(),
    importModel: vi.fn(),
  },
}))
vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
// 删除前的确认框直接确认
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } }
})

function product(patch: Partial<ProductView> = {}): ProductView {
  return {
    productKey: 'pk1',
    name: '三相电表（演示）',
    description: '原描述',
    codec: 'standard-json',
    propertyCount: 12,
    deviceCount: 5,
    onlineCount: 4,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

function properties(count: number): PropertyView[] {
  return Array.from({ length: count }, (_, i) => ({
    identifier: `P${String(i + 1).padStart(2, '0')}`,
    name: `属性${i + 1}`,
    dataType: 'DOUBLE' as const,
    unit: null,
    minValue: null,
    maxValue: null,
    decimals: null,
    enumItems: null,
    description: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }))
}

async function mountAt(path: string, stubs: Record<string, boolean> = {}) {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const replace = vi.spyOn(router, 'replace')
  const push = vi.spyOn(router, 'push')
  const wrapper = mount(ProductDetailView, {
    props: { productKey: 'pk1' },
    global: { plugins: [router, ElementPlus], stubs },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, router, replace, push }
}

function activeTabLabel(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('.el-tabs__item.is-active').text()
}

describe('ProductDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(productApi.get).mockResolvedValue(product())
    vi.mocked(codecApi.list).mockResolvedValue([{ name: 'standard-json', displayName: '标准 JSON' }])
    vi.mocked(thingModelApi.list).mockResolvedValue(properties(12))
    vi.mocked(thingModelApi.remove).mockResolvedValue(undefined)
    vi.mocked(deviceApi.list).mockResolvedValue({
      items: [
        {
          deviceKey: 'dk1',
          name: '1号楼配电室 电表01',
          productKey: 'pk1',
          status: 'ENABLED',
          online: true,
          lastReportAt: null,
          createdAt: '2026-10-01T00:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    })
  })

  describe('tab and query sync', () => {
    it('opens the tab named in ?tab=', async () => {
      const { wrapper } = await mountAt('/products/pk1?tab=model')

      expect(activeTabLabel(wrapper)).toBe('物模型')
      wrapper.unmount()
    })

    it('defaults to basic for a missing or unknown tab', async () => {
      const { wrapper } = await mountAt('/products/pk1?tab=nope')

      expect(activeTabLabel(wrapper)).toBe('基本信息')
      wrapper.unmount()
    })

    it('writes the clicked tab to the query with replace, not push', async () => {
      const { wrapper, router, replace, push } = await mountAt('/products/pk1?tab=model')

      await wrapper.find('#tab-devices').trigger('click')
      await settle()

      expect(replace).toHaveBeenCalledWith({ query: { tab: 'devices' } })
      expect(push).not.toHaveBeenCalled()
      expect(router.currentRoute.value.query).toEqual({ tab: 'devices' })
      expect(activeTabLabel(wrapper)).toBe('设备')
      expect(deviceApi.list).toHaveBeenCalledWith({ productKey: 'pk1', page: 1, size: 10 })

      await wrapper.find('#tab-basic').trigger('click')
      await settle()
      expect(router.currentRoute.value.query).toEqual({})
      wrapper.unmount()
    })

    it('follows query changes made elsewhere', async () => {
      const { wrapper, router } = await mountAt('/products/pk1')

      await router.replace({ query: { tab: 'model' } })
      await settle()

      expect(activeTabLabel(wrapper)).toBe('物模型')
      wrapper.unmount()
    })
  })

  it('shows the product name, key and device counts in the page header', async () => {
    const { wrapper } = await mountAt('/products/pk1')

    const header = wrapper.find('.page-header')
    expect(header.find('h2').text()).toBe('三相电表（演示）')
    expect(header.text()).toContain('pk1')
    expect(header.text()).toContain('设备 5（在线 4）')
    wrapper.unmount()
  })

  it('shows the online dot in the devices tab', async () => {
    const { wrapper } = await mountAt('/products/pk1?tab=devices')

    expect(wrapper.find('.online-dot').text()).toBe('在线')
    wrapper.unmount()
  })

  it('keeps unsaved basic-info edits and refreshes only the counts after a property change', async () => {
    const { wrapper } = await mountAt('/products/pk1')
    const nameInput = wrapper.find('.basic input')
    await nameInput.setValue('改了还没保存')

    vi.mocked(productApi.get).mockResolvedValue(product({ name: '服务端名称', description: '服务端描述', propertyCount: 13 }))
    vi.mocked(thingModelApi.list).mockResolvedValue(properties(13))
    wrapper.findComponent(PropertyDialog).vm.$emit('saved')
    await settle()

    expect(thingModelApi.list).toHaveBeenCalledTimes(2)
    expect((wrapper.find('.basic input').element as HTMLInputElement).value).toBe('改了还没保存')
    expect((wrapper.find('.basic textarea').element as HTMLTextAreaElement).value).toBe('原描述')
    const vm = wrapper.vm as unknown as { product: ProductView }
    expect(vm.product.propertyCount).toBe(13)
    expect(vm.product.name).toBe('三相电表（演示）')
    wrapper.unmount()
  })

  it('pages properties by 10 and moves back to the last page when a delete shrinks the list', async () => {
    // 分页器用空壳替换：Element 自带的越界修正不能替我们的逻辑兜底
    const { wrapper } = await mountAt('/products/pk1?tab=model', { ElPagination: true })
    const table = () => wrapper.findAllComponents(ElTable)[0]
    const pager = () => wrapper.findAllComponents(ElPagination)[0]

    expect(pager().props('total')).toBe(12)
    expect(table().props('data')).toHaveLength(10)

    pager().vm.$emit('update:current-page', 2)
    pager().vm.$emit('current-change', 2)
    await settle()
    expect(table().props('data')).toHaveLength(2)

    // 删掉第 2 页剩下的属性之一后服务端只剩 10 条，第 2 页不再存在
    vi.mocked(thingModelApi.list).mockResolvedValue(properties(10))
    const remove = table().findAll('button').find((b) => b.text() === '删除')
    await remove!.trigger('click')
    await settle()

    expect(thingModelApi.remove).toHaveBeenCalledWith('pk1', 'P11')
    expect(pager().props('total')).toBe(10)
    expect(pager().props('currentPage')).toBe(1)
    expect((wrapper.vm as unknown as { propertyPage: number }).propertyPage).toBe(1)
    expect(table().props('data')).toHaveLength(10)
    wrapper.unmount()
  })
})
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/views/ProductsView.spec.ts src/views/DevicesView.spec.ts src/views/ProductDetailView.spec.ts ; cd ..`
Expected: FAIL — e.g. `.device-count` not found, `.filter-bar` not found, `.page-header` not found, `.online-dot` not found.

- [ ] **Step 7: Rewrite the product list**

Replace `conduit-ui/src/views/ProductsView.vue` with:

```vue
<script setup lang="ts">
/**
 * 产品列表页：服务端分页（useTable，默认每页 10 条）；支持新建、编辑、删除产品，行内按钮可直接进入产品详情的各个标签页，
 * 「设备（在线/总数）」点击进入产品详情的设备标签。首次加载显示骨架屏；没有产品时引导新建。新建时可选解码器，编辑时不能改。
 */

import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { problemMessage } from '@/api/errors'
import type { CodecView, ProductView } from '@/api/types'
import { formatCount, formatDateTime } from '@/utils/format'
import { codecLabel } from '@/utils/thingModel'
import { PAGE_SIZES } from '@/utils/pagination'
import { tabQuery, type ProductTab } from '@/utils/productTabs'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()

const codecs = ref<CodecView[]>([])
const { items, total, page, size, loading, loaded, load, onSizeChange, onPageChange } = useTable<ProductView>(
  (p, s) => productApi.list(p, s),
)

const dialogVisible = ref(false)
const editingKey = ref<string | null>(null)
const saving = ref(false)
const removing = ref(false)
const form = reactive({ name: '', description: '', codec: 'standard-json' })

/** 加载解码器列表，用于新建表单的下拉和列表里的显示名称。 */
async function loadCodecs() {
  try {
    codecs.value = await codecApi.list()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 进入产品详情页并定位到指定标签（默认「基本信息」；默认标签不写入 ?tab=）。 */
function openDetail(row: ProductView, tab: ProductTab = 'basic') {
  void router.push({ name: 'product-detail', params: { productKey: row.productKey }, query: tabQuery({}, tab) })
}

/** 打开新建对话框，表单重置为默认值（解码器默认 standard-json）。 */
function openCreate() {
  editingKey.value = null
  form.name = ''
  form.description = ''
  form.codec = 'standard-json'
  dialogVisible.value = true
}

/** 打开编辑对话框并回填该产品；编辑时请求里不带 codec，保持不变。 */
function openEdit(row: ProductView) {
  editingKey.value = row.productKey
  form.name = row.name
  form.description = row.description ?? ''
  form.codec = row.codec
  dialogVisible.value = true
}

/** 保存新建或编辑（由 editingKey 区分）。名称必填；保存中忽略重复点击；成功后关闭对话框并刷新列表。 */
async function save() {
  if (saving.value) return
  if (!form.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  saving.value = true
  const req = { name: form.name.trim(), description: form.description.trim() || null }
  try {
    if (editingKey.value) {
      await productApi.update(editingKey.value, req)
    } else {
      await productApi.create({ ...req, codec: form.codec })
    }
    dialogVisible.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    saving.value = false
  }
}

/** 删除产品：先确认再请求；成功后重新加载（删掉当前页最后一条时 useTable 自动退到上一页）。 */
async function remove(row: ProductView) {
  if (removing.value) return
  try {
    await ElMessageBox.confirm(`确定删除产品「${row.name}」？`, '删除产品', { type: 'warning' })
  } catch {
    return
  }
  removing.value = true
  try {
    await productApi.remove(row.productKey)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    removing.value = false
  }
}

onMounted(() => {
  void load()
  void loadCodecs()
})
</script>

<template>
  <div>
    <PageHeader title="产品" subtitle="一类设备的模板：定义物模型（属性）与解码器，设备归属于产品。">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建产品</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">{{ row.name }}</router-link>
              <div v-if="row.description" class="cd-muted">{{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="productKey" label="产品 Key" width="190" />
          <el-table-column label="解码器" width="120">
            <template #default="{ row }">{{ codecLabel(codecs, row.codec) }}</template>
          </el-table-column>
          <el-table-column prop="propertyCount" label="属性数" width="90" />
          <el-table-column label="设备（在线/总数）" width="150">
            <template #default="{ row }">
              <router-link
                class="device-count"
                :to="{ name: 'product-detail', params: { productKey: row.productKey }, query: tabQuery({}, 'devices') }"
              >{{ formatCount(row.onlineCount) }} / {{ row.deviceCount }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" @click="openDetail(row, 'model')">物模型</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有产品，先创建一个产品">
              <el-button type="primary" @click="openCreate">新建产品</el-button>
            </el-empty>
          </template>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          class="cd-pager"
          layout="total, sizes, prev, pager, next"
          :page-sizes="PAGE_SIZES"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingKey ? '编辑产品' : '新建产品'"
      width="min(480px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px" @submit.prevent="save">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" maxlength="512" />
        </el-form-item>
        <el-form-item v-if="!editingKey" label="解码器">
          <el-select v-model="form.codec">
            <el-option v-for="c in codecs" :key="c.name" :label="c.displayName" :value="c.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
```

- [ ] **Step 8: Rewrite the device list**

Replace `conduit-ui/src/views/DevicesView.vue` with:

```vue
<script setup lang="ts">
/**
 * 设备列表页：筛选栏（产品、关键字，回车即查询）+ 服务端分页（useTable，默认每页 10 条）；在线状态用圆点，启用状态用标签，
 * 最后上报显示相对时间。支持新建设备、启用/禁用、重置密钥、删除，行内可跳到设备详情与所属产品。
 * 创建设备和重置密钥的响应里有密钥明文，通过 SecretDialog 只展示一次。首次加载显示骨架屏，没有设备时引导新建。
 */

import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { problemMessage } from '@/api/errors'
import type { DeviceStatus, DeviceView, ProductView } from '@/api/types'
import { PAGE_SIZES } from '@/utils/pagination'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import OnlineDot from '@/components/OnlineDot.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import SecretDialog from '@/components/SecretDialog.vue'

const router = useRouter()

const products = ref<ProductView[]>([])
const productNames = computed(() => new Map(products.value.map((p) => [p.productKey, p.name])))
const filters = reactive({ productKey: '', keyword: '' })
const filtered = computed(() => Boolean(filters.productKey || filters.keyword.trim()))

const { items, total, page, size, loading, loaded, load, search, onSizeChange, onPageChange } = useTable<DeviceView>(
  (p, s) =>
    deviceApi.list({
      productKey: filters.productKey || undefined,
      keyword: filters.keyword.trim() || undefined,
      page: p,
      size: s,
    }),
)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ productKey: '', name: '' })

const secretVisible = ref(false)
const secret = reactive({ deviceKey: '', value: '' })

// 行级操作进行中的设备 Key，防止重复触发
const busy = reactive(new Set<string>())

/** 加载产品列表，供筛选下拉、新建表单和产品名称显示使用（最多取 100 个）。 */
async function loadProducts() {
  try {
    products.value = (await productApi.list(1, 100)).items
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 清空筛选条件并回到第 1 页查询。 */
function reset() {
  filters.productKey = ''
  filters.keyword = ''
  void search()
}

/** 打开新建对话框：产品默认取当前筛选的产品，没有筛选则取第一个产品。 */
function openCreate() {
  createForm.productKey = filters.productKey || products.value[0]?.productKey || ''
  createForm.name = ''
  createVisible.value = true
}

/** 创建设备：成功后关闭对话框、展示一次性密钥并刷新列表。创建中忽略重复点击。 */
async function create() {
  if (creating.value) {
    return
  }
  if (!createForm.productKey || !createForm.name.trim()) {
    ElMessage.warning('请选择产品并填写设备名称')
    return
  }
  creating.value = true
  try {
    const res = await deviceApi.create(createForm.productKey, createForm.name.trim())
    createVisible.value = false
    showSecret(res.device.deviceKey, res.deviceSecret)
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    creating.value = false
  }
}

/** 弹出密钥对话框，展示设备密钥（只此一次）。 */
function showSecret(deviceKey: string, value: string) {
  secret.deviceKey = deviceKey
  secret.value = value
  secretVisible.value = true
}

/**
 * 启用/禁用设备（操作列按钮）：切换为相反状态，成功后用服务端返回的状态回写该行；失败时提示，该行保持不变。
 * 同一设备操作进行中时忽略重复触发。
 */
async function toggleStatus(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  const next: DeviceStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    const updated = await deviceApi.update(row.deviceKey, row.name, next)
    row.status = updated.status
    ElMessage.success(updated.status === 'ENABLED' ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 重置设备密钥：先确认（旧密钥立即失效），确认后展示新密钥。用户取消则不请求。同一设备操作进行中时忽略重复触发。 */
async function resetSecret(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    try {
      await ElMessageBox.confirm(`重置后设备「${row.name}」需要使用新密钥重新连接，确定重置？`, '重置密钥', {
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      const res = await deviceApi.resetSecret(row.deviceKey)
      showSecret(res.deviceKey, res.deviceSecret)
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 删除设备：先确认再请求；成功后重新加载（删掉当前页最后一条时 useTable 自动退到上一页）。 */
async function remove(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    try {
      await ElMessageBox.confirm(`确定删除设备「${row.name}」？历史数据会随保留策略过期。`, '删除设备', {
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      await deviceApi.remove(row.deviceKey)
      ElMessage.success('已删除')
      await load()
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 进入页面：产品列表与设备列表并行加载（产品名称只用于显示，晚到也会更新）。 */
onMounted(() => {
  void loadProducts()
  void load()
})
</script>

<template>
  <div>
    <PageHeader title="设备" subtitle="接入平台的设备：查看在线状态与最后上报，管理启用状态与密钥。">
      <template #actions>
        <el-button type="primary" :icon="Plus" :disabled="products.length === 0" @click="openCreate">新建设备</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <FilterBar @search="search" @reset="reset">
        <el-form-item label="产品">
          <el-select v-model="filters.productKey" clearable placeholder="全部" class="product-select">
            <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" class="keyword" placeholder="名称或设备 Key" clearable />
        </el-form-item>
      </FilterBar>

      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <router-link :to="{ name: 'device-detail', params: { deviceKey: row.deviceKey } }">{{ row.name }}</router-link>
              <div class="cd-muted">{{ row.deviceKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="160">
            <template #default="{ row }">
              <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">
                {{ productNames.get(row.productKey) ?? row.productKey }}
              </router-link>
            </template>
          </el-table-column>
          <el-table-column label="在线" width="90">
            <template #default="{ row }"><OnlineDot :online="row.online" /></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后上报" width="120">
            <template #default="{ row }"><RelativeTime :value="row.lastReportAt" /></template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link type="primary" @click="router.push({ name: 'device-detail', params: { deviceKey: row.deviceKey } })">
                详情
              </el-button>
              <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" :loading="busy.has(row.deviceKey)" @click="toggleStatus(row)">
                {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
              </el-button>
              <el-button link type="warning" @click="resetSecret(row)">重置密钥</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="filtered" description="没有符合条件的设备">
              <el-button @click="reset">重置筛选</el-button>
            </el-empty>
            <el-empty v-else :description="products.length === 0 ? '还没有产品，先到「产品」页创建产品' : '还没有设备，先新建一台设备'">
              <el-button v-if="products.length === 0" type="primary" @click="router.push({ name: 'products' })">去创建产品</el-button>
              <el-button v-else type="primary" @click="openCreate">新建设备</el-button>
            </el-empty>
          </template>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          class="cd-pager"
          layout="total, sizes, prev, pager, next"
          :page-sizes="PAGE_SIZES"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>
    </div>

    <el-dialog v-model="createVisible" title="新建设备" width="min(480px, 92vw)" :close-on-click-modal="false">
      <el-form label-width="80px" @submit.prevent="create">
        <el-form-item label="产品" required>
          <el-select v-model="createForm.productKey" style="width: 100%">
            <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <SecretDialog v-model="secretVisible" :device-key="secret.deviceKey" :secret="secret.value" />
  </div>
</template>

<style scoped>
.product-select {
  width: 200px;
}
</style>
```

- [ ] **Step 9: Rewrite the product detail page**

Replace `conduit-ui/src/views/ProductDetailView.vue` with:

```vue
<script setup lang="ts">
/**
 * 产品详情页：页头显示产品名称、产品 Key（可复制）与设备数（在线/总数），产品名称同时写入面包屑与浏览器标题；
 * 下方用 ?tab= 区分三个标签（router.replace 切换，不产生历史记录）：
 * - 基本信息：编辑名称、描述、解码器；
 * - 物模型：属性的增删改、整体导入导出；属性一次取回，前端分页（useTable，默认每页 10 条，删除后页码自动修正）；
 * - 设备：该产品下的设备，服务端分页（useTable），每次切到该标签都重新加载以保持在线状态最新。
 */

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import { productApi } from '@/api/products'
import { deviceApi } from '@/api/devices'
import { codecApi } from '@/api/codecs'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { CodecView, DeviceView, ProductView, PropertyView } from '@/api/types'
import { dataTypeLabel, formatRange } from '@/utils/thingModel'
import { formatCount, formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { PAGE_SIZES, pageSlice } from '@/utils/pagination'
import { tabFromQuery, tabQuery, type ProductTab } from '@/utils/productTabs'
import { downloadJson } from '@/utils/download'
import { useTable } from '@/composables/useTable'
import { useBreadcrumb } from '@/composables/useBreadcrumb'
import PageHeader from '@/components/PageHeader.vue'
import OnlineDot from '@/components/OnlineDot.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import PropertyDialog from '@/components/PropertyDialog.vue'
import ThingModelImportDialog from '@/components/ThingModelImportDialog.vue'

const props = defineProps<{ productKey: string }>()

const route = useRoute()
const router = useRouter()
const { setName } = useBreadcrumb()

// 当前标签与 ?tab= 同步；用 replace 切换，不产生历史记录
const activeTab = computed<ProductTab>({
  get: () => tabFromQuery(route.query.tab),
  set: (tab) => {
    void router.replace({ query: tabQuery(route.query, tab) })
  },
})

const product = ref<ProductView | null>(null)
const codecs = ref<CodecView[]>([])
const properties = ref<PropertyView[]>([])
const fetchingProperties = ref(false)
const basic = reactive({ name: '', description: '', codec: '' })
const savingBasic = ref(false)
const dialogVisible = ref(false)
const editing = ref<PropertyView | null>(null)
const importVisible = ref(false)

// 属性：一次取回全部，前端分页
const {
  items: pagedProperties,
  total: propertyTotal,
  page: propertyPage,
  size: propertySize,
  load: loadPropertyPage,
  onSizeChange: onPropertySizeChange,
  onPageChange: onPropertyPageChange,
} = useTable<PropertyView>((page, size) =>
  Promise.resolve({ items: pageSlice(properties.value, page, size), total: properties.value.length, page, size }),
)

// 设备：服务端分页
const {
  items: devices,
  total: deviceTotal,
  page: devicePage,
  size: deviceSize,
  loading: loadingDevices,
  load: loadDevices,
  onSizeChange: onDeviceSizeChange,
  onPageChange: onDevicePageChange,
} = useTable<DeviceView>((page, size) => deviceApi.list({ productKey: props.productKey, page, size }))

/** 页头里的设备计数文案，如「设备 5（在线 4）」；在线数未知时显示「—」 */
const deviceSummary = computed(() =>
  product.value ? `设备 ${product.value.deviceCount}（在线 ${formatCount(product.value.onlineCount)}）` : '',
)

/** 加载产品并回填基本信息表单（会覆盖表单里未保存的修改，只在进入页面时调用）；产品名称写入面包屑。 */
async function loadProduct() {
  try {
    product.value = await productApi.get(props.productKey)
    basic.name = product.value.name
    basic.description = product.value.description ?? ''
    basic.codec = product.value.codec
    setName(product.value.name)
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 属性变化后只刷新属性数与设备计数，不重置基本信息表单里尚未保存的修改 */
async function refreshCounts() {
  try {
    const fresh = await productApi.get(props.productKey)
    if (product.value) {
      product.value = {
        ...product.value,
        propertyCount: fresh.propertyCount,
        deviceCount: fresh.deviceCount,
        onlineCount: fresh.onlineCount,
      }
    }
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 复制产品 Key 到剪贴板并提示结果。 */
async function copyProductKey() {
  if (await copyText(props.productKey)) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

/** 加载解码器列表，用于基本信息的下拉选项。 */
async function loadCodecs() {
  try {
    codecs.value = await codecApi.list()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 加载产品的全部属性，再按当前页码切片（属性变少导致当前页不存在时 useTable 退到最后一页）。 */
async function loadProperties() {
  fetchingProperties.value = true
  try {
    properties.value = await thingModelApi.list(props.productKey)
    await loadPropertyPage()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    fetchingProperties.value = false
  }
}

/** 保存基本信息（名称必填，解码器一并提交）。成功后用服务端返回值更新产品与面包屑；保存中忽略重复点击。 */
async function saveBasic() {
  if (savingBasic.value) return
  if (!basic.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  savingBasic.value = true
  try {
    product.value = await productApi.update(props.productKey, {
      name: basic.name.trim(),
      description: basic.description.trim() || null,
      codec: basic.codec,
    })
    setName(product.value.name)
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    savingBasic.value = false
  }
}

/** 打开属性对话框，新增模式。 */
function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

/** 打开属性对话框，编辑模式。 */
function openEdit(row: PropertyView) {
  editing.value = row
  dialogVisible.value = true
}

/** 删除属性：先确认再请求（历史数据不会删除），成功后刷新属性列表与计数。 */
async function remove(row: PropertyView) {
  try {
    await ElMessageBox.confirm(`确定删除属性「${row.name}（${row.identifier}）」？历史数据不会删除。`, '删除属性', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await thingModelApi.remove(props.productKey, row.identifier)
    ElMessage.success('已删除')
    await onPropertiesChanged()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 导出物模型并下载为 {productKey}.thing-model.json。 */
async function exportModel() {
  try {
    downloadJson(`${props.productKey}.thing-model.json`, await thingModelApi.exportModel(props.productKey))
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 属性新增、编辑、删除、导入后统一刷新：并行重载属性列表和产品计数。 */
async function onPropertiesChanged() {
  await Promise.all([loadProperties(), refreshCounts()])
}

// 每次切到「设备」标签都重新拉取，在线状态保持最新
watch(
  activeTab,
  (tab) => {
    if (tab === 'devices') {
      void loadDevices()
    }
  },
  { immediate: true },
)

onMounted(() => {
  void loadProduct()
  void loadCodecs()
  void loadProperties()
})
</script>

<template>
  <div>
    <PageHeader :title="product?.name ?? productKey">
      <template #subtitle>
        产品 Key：<code>{{ productKey }}</code>
        <el-button link type="primary" :icon="DocumentCopy" @click="copyProductKey">复制</el-button>
        <span v-if="product" class="summary"> · {{ deviceSummary }}</span>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本信息" name="basic">
          <el-form label-width="96px" class="basic" @submit.prevent="saveBasic">
            <el-form-item label="名称" required>
              <el-input v-model="basic.name" maxlength="128" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="basic.description" type="textarea" maxlength="512" />
            </el-form-item>
            <el-form-item label="解码器">
              <el-select v-model="basic.codec">
                <el-option v-for="c in codecs" :key="c.name" :label="c.displayName" :value="c.name" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingBasic" @click="saveBasic">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="物模型" name="model">
          <div class="toolbar">
            <span class="cd-muted">共 {{ properties.length }} 个属性（上限 200）</span>
            <div>
              <el-button @click="importVisible = true">导入</el-button>
              <el-button @click="exportModel">导出</el-button>
              <el-button type="primary" @click="openCreate">新增属性</el-button>
            </div>
          </div>
          <el-table v-loading="fetchingProperties" :data="pagedProperties">
            <el-table-column prop="identifier" label="标识" width="160" />
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ dataTypeLabel(row.dataType) }}</template>
            </el-table-column>
            <el-table-column label="单位" width="90">
              <template #default="{ row }">{{ row.unit ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="范围" width="160">
              <template #default="{ row }">{{ formatRange(row.minValue, row.maxValue) }}</template>
            </el-table-column>
            <el-table-column label="小数位数" width="100">
              <template #default="{ row }">{{ row.decimals ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无属性：未定义物模型时按 V0.1 规则接收数据（数值一律浮点）">
                <el-button type="primary" @click="openCreate">新增属性</el-button>
                <el-button @click="importVisible = true">导入物模型</el-button>
              </el-empty>
            </template>
          </el-table>
          <el-pagination
            v-model:current-page="propertyPage"
            v-model:page-size="propertySize"
            class="cd-pager"
            layout="total, sizes, prev, pager, next"
            :page-sizes="PAGE_SIZES"
            :total="propertyTotal"
            @current-change="onPropertyPageChange"
            @size-change="onPropertySizeChange"
          />
        </el-tab-pane>

        <el-tab-pane label="设备" name="devices">
          <el-table v-loading="loadingDevices" :data="devices">
            <el-table-column label="名称" min-width="180">
              <template #default="{ row }">
                <router-link :to="{ name: 'device-detail', params: { deviceKey: row.deviceKey } }">{{ row.name }}</router-link>
              </template>
            </el-table-column>
            <el-table-column prop="deviceKey" label="设备 Key" width="190" />
            <el-table-column label="在线" width="90">
              <template #default="{ row }"><OnlineDot :online="row.online" /></template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最后上报" width="120">
              <template #default="{ row }"><RelativeTime :value="row.lastReportAt" /></template>
            </el-table-column>
            <el-table-column label="创建时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <template #empty>
              <el-empty description="该产品下暂无设备">
                <el-button type="primary" @click="router.push({ name: 'devices' })">去设备页新建</el-button>
              </el-empty>
            </template>
          </el-table>
          <el-pagination
            v-model:current-page="devicePage"
            v-model:page-size="deviceSize"
            class="cd-pager"
            layout="total, sizes, prev, pager, next"
            :page-sizes="PAGE_SIZES"
            :total="deviceTotal"
            @current-change="onDevicePageChange"
            @size-change="onDeviceSizeChange"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <PropertyDialog v-model="dialogVisible" :product-key="productKey" :property="editing" @saved="onPropertiesChanged" />
    <ThingModelImportDialog v-model="importVisible" :product-key="productKey" @imported="onPropertiesChanged" />
  </div>
</template>

<style scoped>
.basic {
  max-width: 560px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-2);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--cd-space-3);
}
</style>
```

- [ ] **Step 10: Run the view specs and the whole frontend**

Run: `cd conduit-ui && npx vitest run src/views ; cd ..`
Expected: all PASS.
Run: `cd conduit-ui && npm test && npm run build ; cd ..`
Expected: all specs PASS; type check clean; build succeeds. `grep -rn "createRequestSeq\|clampPage" conduit-ui/src/views` prints nothing (the views no longer hand-roll paging).

- [ ] **Step 11: Manual check against a dev server**

With `VITE_API_TARGET=http://<docker-host>:8091 npm run dev`: the product list shows 「设备（在线/总数）」 such as `100 / 100` for 三相电表（演示）, and the cell opens the product's 设备 tab; the device list filter bar queries on Enter and 重置 clears it; 在线 shows a green dot, 最后上报 reads 「刚刚」 or 「N 分钟前」 with the full time on hover; paging defaults to 10 per page; both pages show a skeleton on first load. Check the same pages in 深色.

- [ ] **Step 12: Commit**

```bash
git add conduit-ui/src/composables/useTable.ts conduit-ui/src/composables/useTable.spec.ts \
  conduit-ui/src/components/FilterBar.vue conduit-ui/src/components/FilterBar.spec.ts \
  conduit-ui/src/components/OnlineDot.vue conduit-ui/src/components/OnlineDot.spec.ts \
  conduit-ui/src/components/RelativeTime.vue conduit-ui/src/components/RelativeTime.spec.ts \
  conduit-ui/src/utils/relativeTime.ts conduit-ui/src/utils/relativeTime.spec.ts \
  conduit-ui/src/utils/format.ts conduit-ui/src/utils/format.spec.ts conduit-ui/src/api/types.ts \
  conduit-ui/src/views/ProductsView.vue conduit-ui/src/views/ProductsView.spec.ts \
  conduit-ui/src/views/DevicesView.vue conduit-ui/src/views/DevicesView.spec.ts \
  conduit-ui/src/views/ProductDetailView.vue conduit-ui/src/views/ProductDetailView.spec.ts \
  conduit-ui/src/views/DeviceDetailView.spec.ts
git commit -m "feat(ui): shared list kit and product device counts on migrated list pages"
```

---
## Task 6: Device detail: info card, connection dialog, metric cards with sparklines, card → history

**Files:**
- Create: `conduit-ui/src/utils/metricCards.ts`, `conduit-ui/src/utils/sparkline.ts`
- Modify: `conduit-ui/src/utils/deviceTabs.ts` (full replacement below), `conduit-ui/src/utils/historyProperties.ts` (append `initialSelection`)
- Create: `conduit-ui/src/components/Sparkline.vue`, `MetricCard.vue`, `LatestValuesPanel.vue`, `DeviceInfoCard.vue`, `ConnectionDialog.vue`
- Modify: `conduit-ui/src/components/HistoryPanel.vue` (`initialProps`)
- Modify: `conduit-ui/src/views/DeviceDetailView.vue` (full replacement below)
- Test: `conduit-ui/src/utils/metricCards.spec.ts`, `conduit-ui/src/utils/sparkline.spec.ts`, `conduit-ui/src/components/Sparkline.spec.ts`, `conduit-ui/src/components/MetricCard.spec.ts`, `conduit-ui/src/components/LatestValuesPanel.spec.ts`, `conduit-ui/src/components/ConnectionDialog.spec.ts` (new)
- Test: `conduit-ui/src/utils/deviceTabs.spec.ts`, `conduit-ui/src/utils/historyProperties.spec.ts`, `conduit-ui/src/components/HistoryPanel.spec.ts` (additions), `conduit-ui/src/views/DeviceDetailView.spec.ts` (full replacement below)

**Interfaces:**
- Consumes: Task 3 `useUiStore` (`latestView`, `setLatestView`), `chartTheme`/`useTheme`/`applyTheme`; Task 4 `useBreadcrumb`; Task 5 `OnlineDot`, `RelativeTime`, `ProductView` with counts; existing `historyApi.query(deviceKey, properties, from, to)`, `HistoryView`/`SeriesView`/`PointView`/`PropertyView`/`DataType`, `echarts`/`ECharts`, `createVisiblePoller`, `usePolling`, `createRequestSeq`, `formatValue` (`utils/connection.ts`), `buildConnectionInfo`, `formatPointValue`/`pointLabel`, `formatDateTime`, `copyText`, `propertyCandidates`/`defaultSelection`/`MAX_SELECTED`/`PropertyCandidate`.
- Produces:
  - `utils/metricCards.ts`: `type SparkPoint = [number, number]`, `MAX_SPARKLINES = 8`, `SPARK_WINDOW_MS = 3_600_000`, `interface MetricCardModel { identifier; title; valueText; unit: string | null; state: boolean; ts: number }`, `toMetricCards(values, model)`, `sparklineIdentifiers(cards)`, `sparklineSeries(view): Record<string, SparkPoint[]>`.
  - `utils/sparkline.ts`: `sparklineOption(points: readonly SparkPoint[]): EChartsCoreOption`.
  - `utils/deviceTabs.ts`: `deviceTabQuery` now drops `props` outside the history tab; `historyQuery(current, identifier)`; `propsFromQuery(value): string[]`.
  - `utils/historyProperties.ts`: `initialSelection(candidates, requested): string[]`.
  - Components: `<Sparkline :points>`, `<MetricCard :card :points @select>`, `<LatestValuesPanel :device-key :values :model :last-refreshed @open-history>`, `<DeviceInfoCard :device :product-name @connection @edit>`, `<ConnectionDialog v-model :product-key :device-key>`; `HistoryPanel` gains optional prop `initialProps?: string[]`.

**Decisions recorded here:**
- `HistoryPanel` stays router-free (its spec mounts it without a router). The detail view parses `?props=` with `propsFromQuery` and passes it as `initialProps`; the panel filters it against its candidates (`initialSelection`: known identifiers in the given order, at most 8; none left → the default first-4 numeric selection). The query is read once when the panel mounts.
- Clicking a card uses `router.replace` (like all tab changes) with `historyQuery`, which keeps other query params. Leaving the history tab drops `props`, so a later visit to 历史 starts from the default selection.
- Card kind: a card is a *state* card (text label, no sparkline) when the thing model says BOOL/ENUM/TEXT, or the value is not a number, or the server sent an `enumLabel`. BOOL text is 「是/否」, as in the history charts. The thing model is loaded once by the detail view; if that fails, the value alone decides.
- Card order: thing-model order first, then identifiers not in the model in alphabetical order. The first 8 numeric cards in that order get sparklines; the others get none.
- Sparklines use `createVisiblePoller` (60 s, paused while the page is hidden) and run only while the card view is visible: the table view and the 历史 tab (where `LatestValuesPanel` is unmounted) stop them. The poller restarts only when the set of sparkline identifiers changes, not on every 5 s value refresh. A failed sparkline request keeps the previous lines (the poller swallows the error).
- A sparkline is drawn only with at least 2 points; the series value is the aggregated average (`point[1]` in AGG mode) or the raw value.
- The info card's 「最后上报」 advances with each 5 s values refresh (the newest `ts` of the latest values), so it does not freeze at page load.
- The 「编辑」 dialog edits name and enabled state through the existing `deviceApi.update`. Secret reset and delete stay on the device list.

- [ ] **Step 1: Write the failing helper specs**

Create `conduit-ui/src/utils/metricCards.spec.ts`:

```ts
/** metricCards 的单元测试：卡片顺序、数值与状态文本、趋势线测点选择（前 8 个数值测点）、历史结果转趋势点 */
import { describe, expect, it } from 'vitest'
import type { DataType, HistoryView, PointView, PropertyView } from '@/api/types'
import { MAX_SPARKLINES, sparklineIdentifiers, sparklineSeries, toMetricCards } from './metricCards'

function point(identifier: string, value: number | string | boolean, patch: Partial<PointView> = {}): PointView {
  return { identifier, name: null, value, ts: 1000, unit: null, decimals: null, enumLabel: null, ...patch }
}

function prop(identifier: string, dataType: DataType): PropertyView {
  return {
    identifier,
    name: identifier,
    dataType,
    unit: null,
    minValue: null,
    maxValue: null,
    decimals: null,
    enumItems: null,
    description: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

describe('toMetricCards', () => {
  it('orders cards like the thing model, then unknown identifiers alphabetically', () => {
    const cards = toMetricCards(
      [point('zz', 1), point('Ia', 5), point('aa', 2), point('Ua', 220)],
      [prop('Ua', 'DOUBLE'), prop('Ia', 'DOUBLE')],
    )

    expect(cards.map((c) => c.identifier)).toEqual(['Ua', 'Ia', 'aa', 'zz'])
  })

  it('formats numbers with decimals and unit, and states as text without a unit', () => {
    const cards = toMetricCards(
      [
        point('Ua', 220.123, { name: 'A 相电压', unit: 'V', decimals: 1 }),
        point('pf', 0.98765),
        point('st', 1, { name: '运行状态', enumLabel: '运行', unit: 'x' }),
        point('code', 5),
        point('on', true),
        point('note', 'abc'),
      ],
      [prop('Ua', 'DOUBLE'), prop('pf', 'DOUBLE'), prop('st', 'ENUM'), prop('code', 'ENUM'), prop('on', 'BOOL'), prop('note', 'TEXT')],
    )

    expect(cards).toEqual([
      { identifier: 'Ua', title: 'A 相电压', valueText: '220.1', unit: 'V', state: false, ts: 1000 },
      { identifier: 'pf', title: 'pf', valueText: '0.988', unit: null, state: false, ts: 1000 },
      { identifier: 'st', title: '运行状态', valueText: '运行', unit: null, state: true, ts: 1000 },
      { identifier: 'code', title: 'code', valueText: '5', unit: null, state: true, ts: 1000 },
      { identifier: 'on', title: 'on', valueText: '是', unit: null, state: true, ts: 1000 },
      { identifier: 'note', title: 'note', valueText: 'abc', unit: null, state: true, ts: 1000 },
    ])
  })

  it('decides by the value when the thing model is unavailable', () => {
    const cards = toMetricCards([point('Ua', 220), point('on', false), point('st', 2, { enumLabel: '检修' })], [])

    expect(cards.map((c) => [c.identifier, c.state, c.valueText])).toEqual([
      ['on', true, '否'],
      ['st', true, '检修'],
      ['Ua', false, '220'],
    ])
  })
})

describe('sparklineIdentifiers', () => {
  it('takes the first 8 numeric cards', () => {
    const values = Array.from({ length: 10 }, (_, i) => point(`P${String(i + 1).padStart(2, '0')}`, i))
    const cards = toMetricCards([...values, point('st', 1, { enumLabel: '运行' })], [])

    expect(MAX_SPARKLINES).toBe(8)
    expect(sparklineIdentifiers(cards)).toEqual(['P01', 'P02', 'P03', 'P04', 'P05', 'P06', 'P07', 'P08'])
  })
})

describe('sparklineSeries', () => {
  it('uses the average of aggregated points and the value of raw points, skipping non-numbers', () => {
    const view: HistoryView = {
      deviceKey: 'dk1',
      from: 0,
      to: 3_600_000,
      bucketMs: null,
      series: [
        { identifier: 'Ua', name: null, dataType: 'DOUBLE', unit: null, decimals: null, enumItems: null, mode: 'AGG', points: [[0, 220, 219, 221]] },
        { identifier: 'Ia', name: null, dataType: 'DOUBLE', unit: null, decimals: null, enumItems: null, mode: 'RAW', points: [[0, 5], [10, 6]] },
        { identifier: 'on', name: null, dataType: 'BOOL', unit: null, decimals: null, enumItems: null, mode: 'LAST', points: [[0, true]] },
      ],
    }

    expect(sparklineSeries(view)).toEqual({ Ua: [[0, 220]], Ia: [[0, 5], [10, 6]], on: [] })
  })
})
```

Create `conduit-ui/src/utils/sparkline.spec.ts`:

```ts
/** sparklineOption 的单元测试：单条折线、无坐标轴与提示框、透明背景 */
import { describe, expect, it } from 'vitest'
import { sparklineOption } from './sparkline'

interface SparkParts {
  backgroundColor: string
  xAxis: { type: string; show: boolean }
  yAxis: { show: boolean; scale: boolean }
  tooltip?: unknown
  series: Array<{ type: string; data: number[][]; symbol: string }>
}

describe('sparklineOption', () => {
  it('draws one line without axes on a transparent background', () => {
    const o = sparklineOption([
      [1, 2],
      [3, 4],
    ]) as unknown as SparkParts

    expect(o.backgroundColor).toBe('transparent')
    expect(o.xAxis).toMatchObject({ type: 'time', show: false })
    expect(o.yAxis).toMatchObject({ show: false, scale: true })
    expect(o.tooltip).toBeUndefined()
    expect(o.series).toHaveLength(1)
    expect(o.series[0]).toMatchObject({ type: 'line', symbol: 'none', data: [[1, 2], [3, 4]] })
  })
})
```

In `conduit-ui/src/utils/deviceTabs.spec.ts`, change the header comment to `/** deviceTabs 的单元测试：?tab= 与设备详情标签互转、离开历史标签去掉 ?props=、卡片跳转 query、?props= 解析 */`, change the import line to `import { deviceTabFromQuery, deviceTabQuery, historyQuery, propsFromQuery } from './deviceTabs'`, and append:

```ts
describe('props in the query', () => {
  it('drops props when leaving the history tab and keeps them on it', () => {
    expect(deviceTabQuery({ tab: 'history', props: 'Ua', foo: 'bar' }, 'latest')).toEqual({ foo: 'bar' })
    expect(deviceTabQuery({ props: 'Ua' }, 'history')).toEqual({ tab: 'history', props: 'Ua' })
  })

  it('builds the history query for a clicked card', () => {
    expect(historyQuery({ foo: 'bar' }, 'Ua')).toEqual({ foo: 'bar', tab: 'history', props: 'Ua' })
  })

  it('parses props: comma separated, trimmed, de-duplicated, first of a repeated param', () => {
    expect(propsFromQuery('Ua, Ub,,Ua')).toEqual(['Ua', 'Ub'])
    expect(propsFromQuery(['Ia', 'Ua'])).toEqual(['Ia'])
    expect(propsFromQuery(undefined)).toEqual([])
    expect(propsFromQuery(null)).toEqual([])
    expect(propsFromQuery('')).toEqual([])
  })
})
```

In `conduit-ui/src/utils/historyProperties.spec.ts`, change the import line to `import { DEFAULT_SELECTED, MAX_SELECTED, defaultSelection, initialSelection, propertyCandidates } from './historyProperties'` and append:

```ts
describe('initialSelection', () => {
  const candidates = propertyCandidates(
    [prop('Ua', 'DOUBLE'), prop('Ub', 'DOUBLE'), prop('Uc', 'DOUBLE'), prop('Ia', 'DOUBLE'), prop('Ib', 'DOUBLE'), prop('st', 'ENUM')],
    [],
  )

  it('keeps the requested identifiers that exist, in the requested order', () => {
    expect(initialSelection(candidates, ['st', 'nope', 'Ua'])).toEqual(['st', 'Ua'])
  })

  it('falls back to the default selection when nothing requested exists', () => {
    expect(initialSelection(candidates, ['nope'])).toEqual(defaultSelection(candidates))
    expect(initialSelection(candidates, [])).toEqual(defaultSelection(candidates))
  })

  it('keeps at most MAX_SELECTED identifiers', () => {
    const many = propertyCandidates(
      Array.from({ length: 10 }, (_, i) => prop(`P${i}`, 'DOUBLE')),
      [],
    )

    expect(initialSelection(many, many.map((c) => c.identifier))).toHaveLength(MAX_SELECTED)
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/utils/metricCards.spec.ts src/utils/sparkline.spec.ts src/utils/deviceTabs.spec.ts src/utils/historyProperties.spec.ts ; cd ..`
Expected: FAIL — unresolved `./metricCards`, `./sparkline`; `historyQuery`, `propsFromQuery`, `initialSelection` are not exported.

- [ ] **Step 3: Implement the helpers**

Create `conduit-ui/src/utils/metricCards.ts`:

```ts
/**
 * 设备最新值卡片：把最新值与物模型合成卡片数据（标题、数值文本、单位、是否状态量），
 * 并选出画迷你趋势线的测点（前 8 个数值测点）、把历史查询结果转成趋势点。纯函数，便于单测。
 */
import type { DataType, HistoryView, PointView, PropertyView } from '@/api/types'
import { formatValue } from './connection'

/** 趋势线上的一个点：[时间毫秒, 数值] */
export type SparkPoint = [number, number]

/** 最多为多少个数值测点画趋势线（一次历史查询的上限也是 8） */
export const MAX_SPARKLINES = 8
/** 趋势线的时间窗口：最近 1 小时 */
export const SPARK_WINDOW_MS = 3_600_000

/** 一张指标卡片 */
export interface MetricCardModel {
  identifier: string
  /** 物模型名称，未定义时为标识 */
  title: string
  /** 数值（按小数位）或状态文字（枚举名称、是/否、文本） */
  valueText: string
  /** 单位；状态量没有单位 */
  unit: string | null
  /** 是否为状态量：显示文字标签，不画趋势线 */
  state: boolean
  /** 采样时间（毫秒） */
  ts: number
}

/** 是否按状态量展示：物模型为 BOOL/ENUM/TEXT，或值不是数字，或服务端给出了枚举名称 */
function isState(p: PointView, type: DataType | undefined): boolean {
  if (type === 'BOOL' || type === 'ENUM' || type === 'TEXT') {
    return true
  }
  return typeof p.value !== 'number' || p.enumLabel !== null
}

/** 状态量文字：枚举名称优先；布尔为「是/否」；其他原样 */
function stateText(p: PointView): string {
  if (p.enumLabel) {
    return p.enumLabel
  }
  if (typeof p.value === 'boolean') {
    return p.value ? '是' : '否'
  }
  return formatValue(p.value)
}

/** 数值文字：有小数位定义时按其格式化，否则整数原样、小数最多 3 位 */
function numberText(p: PointView): string {
  return typeof p.value === 'number' && p.decimals !== null && p.decimals !== undefined
    ? p.value.toFixed(p.decimals)
    : formatValue(p.value)
}

/**
 * 生成卡片：顺序为物模型中的属性顺序，物模型未定义的测点按标识字母序排在后面。
 * @param values 设备最新值
 * @param model 产品物模型；取不到时传空数组，此时只按值本身判断数值/状态
 */
export function toMetricCards(values: PointView[], model: PropertyView[]): MetricCardModel[] {
  const order = new Map(model.map((p, i) => [p.identifier, i]))
  const types = new Map(model.map((p) => [p.identifier, p.dataType]))
  return [...values]
    .sort(
      (a, b) =>
        (order.get(a.identifier) ?? Infinity) - (order.get(b.identifier) ?? Infinity) ||
        a.identifier.localeCompare(b.identifier),
    )
    .map((p) => {
      const state = isState(p, types.get(p.identifier))
      return {
        identifier: p.identifier,
        title: p.name ?? p.identifier,
        valueText: state ? stateText(p) : numberText(p),
        unit: state ? null : p.unit,
        state,
        ts: p.ts,
      }
    })
}

/** 画趋势线的测点：按卡片顺序取前 MAX_SPARKLINES 个数值测点 */
export function sparklineIdentifiers(cards: MetricCardModel[]): string[] {
  return cards
    .filter((c) => !c.state)
    .slice(0, MAX_SPARKLINES)
    .map((c) => c.identifier)
}

/**
 * 把一次历史查询结果转成各测点的趋势点：聚合模式取平均值（点的第 2 项），原始模式取值；时间或值不是数字的点跳过。
 * @returns 键为测点标识；查询中有但没有数值点的测点为空数组
 */
export function sparklineSeries(view: HistoryView): Record<string, SparkPoint[]> {
  const result: Record<string, SparkPoint[]> = {}
  for (const s of view.series) {
    result[s.identifier] = s.points
      .filter((p) => typeof p[0] === 'number' && typeof p[1] === 'number')
      .map((p) => [p[0] as number, p[1] as number])
  }
  return result
}
```

Create `conduit-ui/src/utils/sparkline.ts`:

```ts
/** 迷你趋势线的 ECharts option（纯函数）：无坐标轴、无提示框的单条折线加浅色面积，背景透明以适配浅色与深色主题。 */
import type { EChartsCoreOption } from 'echarts/core'
import type { SparkPoint } from './metricCards'

/**
 * 生成迷你趋势线 option。
 * @param points 按时间排序的 [时间毫秒, 数值]
 */
export function sparklineOption(points: readonly SparkPoint[]): EChartsCoreOption {
  return {
    animation: false,
    backgroundColor: 'transparent',
    grid: { left: 0, right: 0, top: 2, bottom: 2 },
    xAxis: { type: 'time', show: false },
    yAxis: { type: 'value', show: false, scale: true },
    series: [
      {
        type: 'line',
        data: points.map((p) => [p[0], p[1]]),
        symbol: 'none',
        silent: true,
        lineStyle: { width: 1.5 },
        areaStyle: { opacity: 0.12 },
      },
    ],
  }
}
```

Replace `conduit-ui/src/utils/deviceTabs.ts` with:

```ts
/**
 * 设备详情页与路由 query 的互相转换：?tab= 为标签（最新值 latest，默认；历史 history），
 * ?props= 为进入历史标签时初始选中的测点（逗号分隔，由点击指标卡片写入）。
 */
import type { LocationQuery, LocationQueryValue } from 'vue-router'

export type DeviceTab = 'latest' | 'history'

const TABS: readonly DeviceTab[] = ['latest', 'history']
const DEFAULT_TAB: DeviceTab = 'latest'

/** 把 ?tab= 解析为设备详情页的标签；重复参数取第一个，未知值回到「最新值」 */
export function deviceTabFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): DeviceTab {
  const first = Array.isArray(value) ? value[0] : value
  return TABS.includes(first as DeviceTab) ? (first as DeviceTab) : DEFAULT_TAB
}

/**
 * 生成切换标签后的 query，保留其他参数；默认标签不写入地址栏。
 * 切到历史以外的标签时去掉 props：它只决定进入历史标签那一次的初始选中。
 */
export function deviceTabQuery(current: LocationQuery, tab: DeviceTab): LocationQuery {
  const next: LocationQuery = { ...current }
  delete next.tab
  if (tab !== DEFAULT_TAB) {
    next.tab = tab
  }
  if (tab !== 'history') {
    delete next.props
  }
  return next
}

/** 点击指标卡片后的 query：切到历史标签并只选中该测点，保留其他参数 */
export function historyQuery(current: LocationQuery, identifier: string): LocationQuery {
  return { ...current, tab: 'history', props: identifier }
}

/** 解析 ?props=：逗号分隔，去掉空白与空项并去重；重复参数取第一个；没有时为空数组 */
export function propsFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): string[] {
  const first = Array.isArray(value) ? value[0] : value
  if (!first) {
    return []
  }
  return [...new Set(first.split(',').map((s) => s.trim()).filter(Boolean))]
}
```

In `conduit-ui/src/utils/historyProperties.ts`, change the header comment to `/** 历史页的测点候选、默认选中与初始选中（来自 ?props=） */` and append:

```ts
/**
 * 初始选中：给定的测点（来自 ?props=）中存在于候选里的部分，保持给定顺序，最多 MAX_SELECTED 个；
 * 一个都不剩（或没有给定）时用默认选中。
 */
export function initialSelection(candidates: PropertyCandidate[], requested: readonly string[]): string[] {
  const known = new Set(candidates.map((c) => c.identifier))
  const picked = requested.filter((id) => known.has(id)).slice(0, MAX_SELECTED)
  return picked.length > 0 ? picked : defaultSelection(candidates)
}
```

- [ ] **Step 4: Run the helper specs to verify they pass**

Run: `cd conduit-ui && npx vitest run src/utils/metricCards.spec.ts src/utils/sparkline.spec.ts src/utils/deviceTabs.spec.ts src/utils/historyProperties.spec.ts ; cd ..`
Expected: all PASS.

- [ ] **Step 5: Write the failing component specs**

Create `conduit-ui/src/components/Sparkline.spec.ts`:

```ts
/** Sparkline 组件测试：按当前主题创建实例、数据变化时重绘、主题切换时按新主题重建、卸载时销毁 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import Sparkline from './Sparkline.vue'
import { echarts } from '@/utils/echarts'
import { applyTheme } from '@/composables/useTheme'

/** 代替 ECharts 实例的假对象 */
interface FakeChart {
  setOption: ReturnType<typeof vi.fn>
  dispose: ReturnType<typeof vi.fn>
  resize: ReturnType<typeof vi.fn>
}

const fake = vi.hoisted(() => ({ charts: [] as FakeChart[] }))

vi.mock('@/utils/echarts', () => ({
  echarts: {
    init: vi.fn(() => {
      const chart: FakeChart = { setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }
      fake.charts.push(chart)
      return chart
    }),
  },
}))

describe('Sparkline', () => {
  beforeEach(() => {
    fake.charts.length = 0
    vi.clearAllMocks()
  })

  afterEach(() => {
    applyTheme(false)
  })

  it('creates the chart with the current theme, redraws on new points and disposes on unmount', async () => {
    const wrapper = mount(Sparkline, { props: { points: [[1, 2], [3, 4]] } })

    expect(echarts.init).toHaveBeenCalledTimes(1)
    expect(vi.mocked(echarts.init).mock.calls[0][1]).toBeUndefined()
    expect(fake.charts[0].setOption).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ points: [[1, 2], [3, 5]] })
    expect(fake.charts[0].setOption).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(fake.charts[0].dispose).toHaveBeenCalled()
  })

  it('rebuilds the chart with the dark theme when the theme changes', async () => {
    const wrapper = mount(Sparkline, { props: { points: [[1, 2], [3, 4]] } })

    applyTheme(true)
    await nextTick()

    expect(fake.charts[0].dispose).toHaveBeenCalled()
    expect(vi.mocked(echarts.init).mock.calls[1][1]).toBe('dark')
    expect(fake.charts[1].setOption).toHaveBeenCalled()
    wrapper.unmount()
  })
})
```

Create `conduit-ui/src/components/MetricCard.spec.ts`:

```ts
/** MetricCard 组件测试：数值 + 单位 + 趋势线、状态量文字标签不画趋势线、点数不足不画、点击与回车发出 select */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import MetricCard from './MetricCard.vue'
import Sparkline from './Sparkline.vue'
import type { MetricCardModel } from '@/utils/metricCards'

vi.mock('@/utils/echarts', () => ({
  echarts: { init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() })) },
}))

function card(patch: Partial<MetricCardModel> = {}): MetricCardModel {
  return { identifier: 'Ua', title: 'A 相电压', valueText: '220.1', unit: 'V', state: false, ts: Date.now() - 5000, ...patch }
}

function mountCard(c: MetricCardModel, points: [number, number][] | null) {
  return mount(MetricCard, { props: { card: c, points }, global: { plugins: [ElementPlus] } })
}

describe('MetricCard', () => {
  it('shows the number with its unit, the identifier, the sparkline and the relative time', () => {
    const wrapper = mountCard(card(), [
      [1, 220],
      [2, 221],
    ])

    expect(wrapper.find('.title').text()).toBe('A 相电压')
    expect(wrapper.find('.identifier').text()).toBe('Ua')
    expect(wrapper.find('.number').text()).toBe('220.1')
    expect(wrapper.find('.unit').text()).toBe('V')
    expect(wrapper.findComponent(Sparkline).exists()).toBe(true)
    expect(wrapper.text()).toContain('刚刚')
  })

  it('shows a state as a text label without a sparkline', () => {
    const wrapper = mountCard(card({ identifier: 'st', title: '运行状态', valueText: '运行', unit: null, state: true }), null)

    expect(wrapper.find('.state-tag').text()).toBe('运行')
    expect(wrapper.find('.number').exists()).toBe(false)
    expect(wrapper.findComponent(Sparkline).exists()).toBe(false)
  })

  it('does not draw a sparkline with fewer than two points', () => {
    expect(mountCard(card(), []).findComponent(Sparkline).exists()).toBe(false)
    expect(mountCard(card(), [[1, 220]]).findComponent(Sparkline).exists()).toBe(false)
  })

  it('emits select with the identifier on click and on Enter', async () => {
    const wrapper = mountCard(card(), null)

    await wrapper.trigger('click')
    await wrapper.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('select')).toEqual([['Ua'], ['Ua']])
  })
})
```

Create `conduit-ui/src/components/LatestValuesPanel.spec.ts`:

```ts
/**
 * LatestValuesPanel 组件测试：一次历史查询取前 8 个数值测点最近 1 小时的趋势、每 60 秒刷新；状态量卡片无趋势线；
 * 切到表格视图后保存偏好并停止趋势请求；点击卡片发出 open-history。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup, ElTable } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import LatestValuesPanel from './LatestValuesPanel.vue'
import MetricCard from './MetricCard.vue'
import Sparkline from './Sparkline.vue'
import { historyApi } from '@/api/history'
import type { HistoryView, PointView } from '@/api/types'
import { useUiStore } from '@/stores/ui'
import { settle } from '@/test/viewTestUtils'

vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/utils/echarts', () => ({
  echarts: { init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() })) },
}))

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)
const IDS = Array.from({ length: 10 }, (_, i) => `P${String(i + 1).padStart(2, '0')}`)

function point(identifier: string, value: number, patch: Partial<PointView> = {}): PointView {
  return { identifier, name: null, value, ts: NOW - 5000, unit: null, decimals: null, enumLabel: null, ...patch }
}

const VALUES: PointView[] = [...IDS.map((id, i) => point(id, i)), point('st', 1, { name: '运行状态', enumLabel: '运行' })]

function view(): HistoryView {
  return {
    deviceKey: 'dk1',
    from: NOW - 3_600_000,
    to: NOW,
    bucketMs: null,
    series: [
      {
        identifier: 'P01',
        name: null,
        dataType: 'DOUBLE',
        unit: null,
        decimals: null,
        enumItems: null,
        mode: 'RAW',
        points: [
          [NOW - 60_000, 1],
          [NOW, 2],
        ],
      },
    ],
  }
}

async function mountPanel() {
  const wrapper = mount(LatestValuesPanel, {
    props: { deviceKey: 'dk1', values: VALUES, model: [], lastRefreshed: NOW },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

function cardOf(wrapper: Awaited<ReturnType<typeof mountPanel>>, identifier: string) {
  return wrapper.findAllComponents(MetricCard).find((c) => c.props('card').identifier === identifier)!
}

describe('LatestValuesPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(NOW)
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(historyApi.query).mockResolvedValue(view())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('queries the last hour of the first 8 numeric properties once and refreshes every 60 seconds', async () => {
    const wrapper = await mountPanel()

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    expect(historyApi.query).toHaveBeenCalledWith('dk1', IDS.slice(0, 8), NOW - 3_600_000, NOW)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(historyApi.query).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('draws sparklines only for numeric cards that have points and shows the enum label', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.findAllComponents(MetricCard)).toHaveLength(11)
    expect(cardOf(wrapper, 'P01').findComponent(Sparkline).exists()).toBe(true)
    expect(cardOf(wrapper, 'P02').findComponent(Sparkline).exists()).toBe(false)
    expect(cardOf(wrapper, 'P09').props('points')).toBeNull()
    expect(cardOf(wrapper, 'st').props('points')).toBeNull()
    expect(cardOf(wrapper, 'st').text()).toContain('运行')
    wrapper.unmount()
  })

  it('switches to the table, remembers it and stops sparkline requests', async () => {
    const wrapper = await mountPanel()

    wrapper.findComponent(ElRadioGroup).vm.$emit('change', 'table')
    await settle()

    expect(useUiStore().latestView).toBe('table')
    expect(wrapper.findComponent(ElTable).exists()).toBe(true)
    expect(wrapper.findAllComponents(MetricCard)).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(180_000)
    expect(historyApi.query).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('emits open-history when a card is clicked', async () => {
    const wrapper = await mountPanel()

    await cardOf(wrapper, 'P01').trigger('click')

    expect(wrapper.emitted('open-history')).toEqual([['P01']])
    wrapper.unmount()
  })

  it('stops sparkline requests on unmount', async () => {
    const wrapper = await mountPanel()

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(180_000)

    expect(historyApi.query).toHaveBeenCalledTimes(1)
  })
})
```

Create `conduit-ui/src/components/ConnectionDialog.spec.ts`:

```ts
/** ConnectionDialog 组件测试：显示 clientId、username 与上报 topic，「关闭」更新 v-model */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConnectionDialog from './ConnectionDialog.vue'
import { settle } from '@/test/viewTestUtils'

describe('ConnectionDialog', () => {
  it('shows the MQTT parameters of the device and closes', async () => {
    const wrapper = mount(ConnectionDialog, {
      props: { modelValue: true, productKey: 'pk1', deviceKey: 'dk1' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    const text = document.body.textContent ?? ''
    expect(text).toContain('conduit/pk1/dk1/up/property')
    expect(text).toContain('设备密钥（创建或重置时显示）')

    const close = Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.trim() === '关闭')
    close!.click()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })
})
```

In `conduit-ui/src/components/HistoryPanel.spec.ts`, insert these tests before the file's final `})`:

```ts
  it('starts with the properties passed in initialProps and ignores unknown ones', async () => {
    const wrapper = mount(HistoryPanel, {
      props: { deviceKey: 'dk1', productKey: 'pk1', initialProps: ['Ub', 'nope'] },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    expect(historyApi.query).toHaveBeenCalledWith('dk1', ['Ub'], NOW - HOUR, NOW)
    wrapper.unmount()
  })

  it('falls back to the default selection when no initial property exists', async () => {
    const wrapper = mount(HistoryPanel, {
      props: { deviceKey: 'dk1', productKey: 'pk1', initialProps: ['nope'] },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    expect(historyApi.query).toHaveBeenCalledWith('dk1', DEFAULT_IDS, NOW - HOUR, NOW)
    wrapper.unmount()
  })
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/components/Sparkline.spec.ts src/components/MetricCard.spec.ts src/components/LatestValuesPanel.spec.ts src/components/ConnectionDialog.spec.ts src/components/HistoryPanel.spec.ts ; cd ..`
Expected: FAIL — unresolved `./Sparkline.vue`, `./MetricCard.vue`, `./LatestValuesPanel.vue`, `./ConnectionDialog.vue`; the `initialProps` test is called with `DEFAULT_IDS` instead of `['Ub']`.

- [ ] **Step 7: Implement the card components**

Create `conduit-ui/src/components/Sparkline.vue`:

```vue
<script setup lang="ts">
/** 迷你趋势线（ECharts 小图）：points 变化时重绘；主题切换时按新主题重建实例；窗口尺寸变化时自适应；卸载时销毁。 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { echarts, type ECharts } from '@/utils/echarts'
import { chartTheme, useTheme } from '@/composables/useTheme'
import { sparklineOption } from '@/utils/sparkline'
import type { SparkPoint } from '@/utils/metricCards'

const props = defineProps<{ points: SparkPoint[] }>()

const el = ref<HTMLElement | null>(null)
const { isDark } = useTheme()
let chart: ECharts | null = null

/** 按当前数据绘制；实例不存在时按当前主题创建 */
function render() {
  if (!el.value) {
    return
  }
  chart ??= echarts.init(el.value, chartTheme(isDark.value))
  chart.setOption(sparklineOption(props.points), true)
}

/** 销毁实例（主题切换、卸载时） */
function dispose() {
  chart?.dispose()
  chart = null
}

/** 容器尺寸变化时自适应 */
function resize() {
  chart?.resize()
}

watch(() => props.points, render)
watch(isDark, () => {
  dispose()
  render()
})

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  dispose()
})
</script>

<template>
  <div ref="el" class="sparkline" />
</template>

<style scoped>
.sparkline {
  width: 100%;
  height: 36px;
}
</style>
```

Create `conduit-ui/src/components/MetricCard.vue`:

```vue
<script setup lang="ts">
/**
 * 单个测点的指标卡片：名称与标识；数值（按小数位）+ 单位，或状态量的文字标签（如「运行」）；
 * 数值测点有至少 2 个点时画最近 1 小时迷你趋势线；底部为相对采样时间。点击或回车发出 select（测点标识）。
 */
import type { MetricCardModel, SparkPoint } from '@/utils/metricCards'
import Sparkline from './Sparkline.vue'
import RelativeTime from './RelativeTime.vue'

defineProps<{ card: MetricCardModel; points: SparkPoint[] | null }>()
const emit = defineEmits<{ select: [identifier: string] }>()
</script>

<template>
  <div
    class="metric-card"
    role="button"
    tabindex="0"
    :title="`查看「${card.title}」的历史曲线`"
    @click="emit('select', card.identifier)"
    @keydown.enter="emit('select', card.identifier)"
  >
    <div class="head">
      <span class="title">{{ card.title }}</span>
      <span class="identifier">{{ card.identifier }}</span>
    </div>
    <div class="value">
      <el-tag v-if="card.state" class="state-tag" size="large" effect="light">{{ card.valueText }}</el-tag>
      <template v-else>
        <span class="number">{{ card.valueText }}</span>
        <span v-if="card.unit" class="unit">{{ card.unit }}</span>
      </template>
    </div>
    <div class="trend">
      <Sparkline v-if="!card.state && points && points.length > 1" :points="points" />
    </div>
    <div class="foot"><RelativeTime :value="card.ts" /></div>
  </div>
</template>

<style scoped>
.metric-card {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-1);
  padding: var(--cd-space-3) var(--cd-space-4);
  background: var(--cd-panel-bg);
  border: 1px solid var(--cd-panel-border);
  border-radius: var(--cd-radius);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.metric-card:hover,
.metric-card:focus-visible {
  border-color: var(--el-color-primary);
  box-shadow: var(--el-box-shadow-light);
  outline: none;
}
.head {
  display: flex;
  justify-content: space-between;
  gap: var(--cd-space-2);
}
.title {
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.identifier {
  flex-shrink: 0;
  color: var(--cd-text-muted);
  font-size: 12px;
}
.value {
  display: flex;
  align-items: baseline;
  min-height: 32px;
}
.number {
  color: var(--el-text-color-primary);
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.unit {
  margin-left: var(--cd-space-1);
  color: var(--cd-text-muted);
}
.trend {
  height: 36px;
}
.foot {
  color: var(--cd-text-muted);
  font-size: 12px;
}
</style>
```

Create `conduit-ui/src/components/LatestValuesPanel.vue`:

```vue
<script setup lang="ts">
/**
 * 设备「最新值」标签页内容：卡片 / 表格两种视图（选择存入外观偏好）。卡片视图每个测点一张 MetricCard，
 * 前 8 个数值测点带最近 1 小时迷你趋势线：一次历史查询取得，每 60 秒刷新（页面隐藏时暂停；表格视图和组件卸载后不再请求）。
 * 数值本身由父组件每 5 秒刷新后通过 values 传入。点击卡片发出 open-history（测点标识）。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { historyApi } from '@/api/history'
import type { PointView, PropertyView } from '@/api/types'
import { useUiStore, type LatestView } from '@/stores/ui'
import { createVisiblePoller } from '@/composables/usePolling'
import { createRequestSeq } from '@/utils/requestSeq'
import {
  SPARK_WINDOW_MS,
  sparklineIdentifiers,
  sparklineSeries,
  toMetricCards,
  type SparkPoint,
} from '@/utils/metricCards'
import { formatPointValue, pointLabel } from '@/utils/pointValue'
import { formatDateTime } from '@/utils/format'
import MetricCard from './MetricCard.vue'

const props = defineProps<{
  deviceKey: string
  values: PointView[]
  model: PropertyView[]
  lastRefreshed: number | null
}>()
const emit = defineEmits<{ 'open-history': [identifier: string] }>()

const SPARK_REFRESH_MS = 60_000

const ui = useUiStore()
const cards = computed(() => toMetricCards(props.values, props.model))
const sparkIds = computed(() => sparklineIdentifiers(cards.value))
const sparks = ref<Record<string, SparkPoint[]>>({})
const seq = createRequestSeq()
const poller = createVisiblePoller(loadSparks, SPARK_REFRESH_MS)

/** 一次查询取得全部趋势线；只有最后发出的请求能写入结果，失败时保留原有趋势线（错误由轮询器吞掉） */
async function loadSparks() {
  const ids = sparkIds.value
  if (ids.length === 0) {
    return
  }
  const id = seq.next()
  const now = Date.now()
  const res = await historyApi.query(props.deviceKey, ids, now - SPARK_WINDOW_MS, now)
  if (seq.isLatest(id)) {
    sparks.value = sparklineSeries(res)
  }
}

/** 某张卡片的趋势点：不画趋势线的测点为 null，尚未取到时为空数组 */
function sparkPoints(identifier: string): SparkPoint[] | null {
  return sparkIds.value.includes(identifier) ? (sparks.value[identifier] ?? []) : null
}

/** 卡片 / 表格切换 */
function onViewChange(value: unknown) {
  ui.setLatestView(value as LatestView)
}

// 只在卡片视图且有数值测点时轮询趋势；测点集合变化（而不是每 5 秒的数值刷新）才重新开始
watch(
  [() => sparkIds.value.join(','), () => ui.latestView],
  ([ids, view]) => {
    if (ids && view === 'cards') {
      poller.start()
    } else {
      poller.stop()
      seq.next()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  poller.dispose()
  seq.next()
})
</script>

<template>
  <div class="latest">
    <div class="bar">
      <span class="cd-muted">每 5 秒刷新 · {{ formatDateTime(lastRefreshed) }}</span>
      <el-radio-group :model-value="ui.latestView" size="small" @change="onViewChange">
        <el-radio-button value="cards">卡片</el-radio-button>
        <el-radio-button value="table">表格</el-radio-button>
      </el-radio-group>
    </div>

    <el-empty v-if="values.length === 0" description="暂无数据：设备上报后在这里显示最新值" />
    <div v-else-if="ui.latestView === 'cards'" class="cards">
      <MetricCard
        v-for="c in cards"
        :key="c.identifier"
        :card="c"
        :points="sparkPoints(c.identifier)"
        @select="(identifier: string) => emit('open-history', identifier)"
      />
    </div>
    <el-table v-else :data="values">
      <el-table-column label="测点" min-width="200">
        <template #default="{ row }">{{ pointLabel(row) }}</template>
      </el-table-column>
      <el-table-column label="值" min-width="140">
        <template #default="{ row }">{{ formatPointValue(row) }}</template>
      </el-table-column>
      <el-table-column label="采样时间" width="200">
        <template #default="{ row }">{{ formatDateTime(row.ts) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.latest {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-3);
}
.bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-2);
  align-items: center;
  justify-content: space-between;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--cd-space-3);
}
</style>
```

Create `conduit-ui/src/components/DeviceInfoCard.vue`:

```vue
<script setup lang="ts">
/**
 * 设备信息卡（设备详情页顶部）：图标、名称、在线状态、启用状态标签；下方一行为所属产品（链接）、设备 Key（可复制）、
 * 最后上报（相对时间）、创建日期；右侧「连接参数」「编辑」按钮分别发出 connection、edit。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Cpu, DocumentCopy, Edit } from '@element-plus/icons-vue'
import type { DeviceView } from '@/api/types'
import { copyText } from '@/utils/clipboard'
import { formatDateTime } from '@/utils/format'
import OnlineDot from './OnlineDot.vue'
import RelativeTime from './RelativeTime.vue'

const props = defineProps<{ device: DeviceView; productName: string | null }>()
const emit = defineEmits<{ connection: []; edit: [] }>()

/** 创建日期 yyyy-MM-dd */
const createdDate = computed(() => formatDateTime(props.device.createdAt).slice(0, 10))

/** 复制设备 Key 到剪贴板并提示结果 */
async function copyKey() {
  if (await copyText(props.device.deviceKey)) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="info-card cd-panel">
    <div class="icon"><el-icon :size="28"><Cpu /></el-icon></div>
    <div class="body">
      <div class="title-row">
        <h2 class="name">{{ device.name }}</h2>
        <OnlineDot :online="device.online" />
        <el-tag :type="device.status === 'ENABLED' ? 'success' : 'info'" size="small">
          {{ device.status === 'ENABLED' ? '启用' : '禁用' }}
        </el-tag>
      </div>
      <div class="meta">
        <span>
          所属产品：<router-link :to="{ name: 'product-detail', params: { productKey: device.productKey } }">
            {{ productName ?? device.productKey }}
          </router-link>
        </span>
        <span>
          设备 Key：<code>{{ device.deviceKey }}</code>
          <el-button link type="primary" :icon="DocumentCopy" aria-label="复制设备 Key" @click="copyKey" />
        </span>
        <span>最后上报：<RelativeTime :value="device.lastReportAt" /></span>
        <span>创建日期：{{ createdDate }}</span>
      </div>
    </div>
    <div class="actions">
      <el-button :icon="Connection" @click="emit('connection')">连接参数</el-button>
      <el-button type="primary" :icon="Edit" @click="emit('edit')">编辑</el-button>
    </div>
  </div>
</template>

<style scoped>
.info-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--cd-space-4);
}
.icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: var(--cd-radius-lg);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.body {
  flex: 1;
  min-width: 240px;
}
.title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--cd-space-3);
}
.name {
  margin: 0;
  font-size: 20px;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-1) var(--cd-space-5);
  margin-top: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  gap: var(--cd-space-2);
}
</style>
```

Create `conduit-ui/src/components/ConnectionDialog.vue`:

```vue
<script setup lang="ts">
/**
 * 连接参数对话框（设备信息卡的「连接参数」）：设备接入 MQTT 所需的地址、端口、clientId、username、上报 topic 与 payload 示例。
 * 密码是设备密钥，只在创建或重置时显示，这里不出现。地址取当前页面的主机名，端口可由构建时 VITE_MQTT_PORT 覆盖（默认 1883）。
 */
import { computed } from 'vue'
import { buildConnectionInfo } from '@/utils/connection'

const props = defineProps<{ modelValue: boolean; productKey: string; deviceKey: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const mqttPort = Number(import.meta.env.VITE_MQTT_PORT) || 1883
const info = computed(() => buildConnectionInfo(props.productKey, props.deviceKey, window.location.hostname, mqttPort))
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="连接参数"
    width="min(640px, 92vw)"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-descriptions :column="1" border>
      <el-descriptions-item label="地址">{{ info.host }}</el-descriptions-item>
      <el-descriptions-item label="端口">{{ info.port }}</el-descriptions-item>
      <el-descriptions-item label="clientId">{{ info.clientId }}</el-descriptions-item>
      <el-descriptions-item label="username">{{ info.username }}</el-descriptions-item>
      <el-descriptions-item label="password">设备密钥（创建或重置时显示）</el-descriptions-item>
      <el-descriptions-item label="上报 topic">{{ info.topic }}</el-descriptions-item>
    </el-descriptions>
    <p class="label">payload 示例</p>
    <pre class="payload">{{ info.payloadExample }}</pre>
    <template #footer>
      <el-button type="primary" @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.label {
  margin: var(--cd-space-4) 0 var(--cd-space-1);
  color: var(--cd-text-muted);
}
.payload {
  margin: 0;
  padding: var(--cd-space-3);
  overflow-x: auto;
  background: var(--el-fill-color-light);
  border-radius: var(--cd-radius-sm);
}
</style>
```

In `conduit-ui/src/components/HistoryPanel.vue`:

1. In the header comment, append before ` */`:

```ts
 * 初始选中的测点可由 initialProps 指定（来自 ?props=），不存在的标识被忽略，一个都不剩时用默认选中（前 4 个数值测点）。
```

2. Change `const props = defineProps<{ deviceKey: string; productKey: string }>()` to:

```ts
const props = defineProps<{ deviceKey: string; productKey: string; initialProps?: string[] }>()
```

3. Change the `historyProperties` import to:

```ts
import { initialSelection, MAX_SELECTED, propertyCandidates, type PropertyCandidate } from '@/utils/historyProperties'
```

4. In `onMounted`, change `selected.value = defaultSelection(candidates.value)` to:

```ts
    selected.value = initialSelection(candidates.value, props.initialProps ?? [])
```

- [ ] **Step 8: Run the component specs to verify they pass**

Run: `cd conduit-ui && npx vitest run src/components ; cd ..`
Expected: all PASS.

- [ ] **Step 9: Write the failing device detail spec**

Replace `conduit-ui/src/views/DeviceDetailView.spec.ts` with:

```ts
/**
 * DeviceDetailView 组件测试：信息卡、「最新值 | 历史」标签与 ?tab= 同步（router.replace）、历史标签才挂载 HistoryPanel 并传入 ?props=、
 * 点击卡片跳到历史并只选中该测点、连接参数弹窗、编辑名称与启用状态。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import DeviceDetailView from './DeviceDetailView.vue'
import HistoryPanel from '@/components/HistoryPanel.vue'
import LatestValuesPanel from '@/components/LatestValuesPanel.vue'
import ConnectionDialog from '@/components/ConnectionDialog.vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { thingModelApi } from '@/api/thingModel'
import type { DeviceView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
// 被 stub 的面板模块仍会被导入：一并 mock 掉它们依赖的接口与 ECharts
vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), exportModel: vi.fn(), importModel: vi.fn() },
}))
vi.mock('@/utils/echarts', () => ({ echarts: { init: vi.fn(), connect: vi.fn(), disconnect: vi.fn() } }))

function device(patch: Partial<DeviceView> = {}): DeviceView {
  return {
    deviceKey: 'dk1',
    name: '1号楼配电室 电表01',
    productKey: 'pk1',
    status: 'ENABLED',
    online: true,
    lastReportAt: null,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const replace = vi.spyOn(router, 'replace')
  const push = vi.spyOn(router, 'push')
  const wrapper = mount(DeviceDetailView, {
    props: { deviceKey: 'dk1' },
    global: { plugins: [pinia, router, ElementPlus], stubs: { HistoryPanel: true, LatestValuesPanel: true } },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, router, replace, push }
}

function activeTabLabel(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('.el-tabs__item.is-active').text()
}

describe('DeviceDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(deviceApi.get).mockResolvedValue(device())
    vi.mocked(deviceApi.latest).mockResolvedValue({ deviceKey: 'dk1', values: [] })
    vi.mocked(thingModelApi.list).mockResolvedValue([])
    vi.mocked(productApi.get).mockResolvedValue({
      productKey: 'pk1',
      name: '三相电表（演示）',
      description: null,
      codec: 'standard-json',
      propertyCount: 8,
      deviceCount: 100,
      onlineCount: 100,
      createdAt: '2026-10-01T00:00:00Z',
    })
  })

  it('shows the info card and the latest values without mounting the history panel', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    const card = wrapper.find('.info-card')
    expect(card.find('h2').text()).toBe('1号楼配电室 电表01')
    expect(card.text()).toContain('三相电表（演示）')
    expect(card.text()).toContain('在线')
    expect(card.text()).toContain('启用')
    expect(activeTabLabel(wrapper)).toBe('最新值')
    expect(wrapper.findComponent(LatestValuesPanel).props('deviceKey')).toBe('dk1')
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the history tab from the query and passes the device, product and ?props=', async () => {
    const { wrapper } = await mountAt('/devices/dk1?tab=history&props=Ua,Ub')

    expect(activeTabLabel(wrapper)).toBe('历史')
    const panel = wrapper.findComponent(HistoryPanel)
    expect(panel.props('deviceKey')).toBe('dk1')
    expect(panel.props('productKey')).toBe('pk1')
    expect(panel.props('initialProps')).toEqual(['Ua', 'Ub'])
    expect(wrapper.findComponent(LatestValuesPanel).exists()).toBe(false)
    wrapper.unmount()
  })

  it('writes the clicked tab to the query with replace, not push', async () => {
    const { wrapper, router, replace, push } = await mountAt('/devices/dk1')

    await wrapper.find('#tab-history').trigger('click')
    await settle()

    expect(replace).toHaveBeenCalledWith({ query: { tab: 'history' } })
    expect(push).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query).toEqual({ tab: 'history' })
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(true)

    await wrapper.find('#tab-latest').trigger('click')
    await settle()
    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the history of a clicked card with only that property selected', async () => {
    const { wrapper, router, replace } = await mountAt('/devices/dk1')

    wrapper.findComponent(LatestValuesPanel).vm.$emit('open-history', 'Ua')
    await settle()

    expect(replace).toHaveBeenCalledWith({ query: { tab: 'history', props: 'Ua' } })
    expect(router.currentRoute.value.query).toEqual({ tab: 'history', props: 'Ua' })
    expect(wrapper.findComponent(HistoryPanel).props('initialProps')).toEqual(['Ua'])
    wrapper.unmount()
  })

  it('opens the connection dialog from the info card', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    await wrapper.findAll('.info-card button').find((b) => b.text() === '连接参数')!.trigger('click')

    expect(wrapper.findComponent(ConnectionDialog).props('modelValue')).toBe(true)
    wrapper.unmount()
  })

  it('edits the name and the enabled state', async () => {
    vi.mocked(deviceApi.update).mockResolvedValue(device({ name: '1号楼配电室 电表99', status: 'DISABLED' }))
    const { wrapper } = await mountAt('/devices/dk1')
    const vm = wrapper.vm as unknown as {
      editVisible: boolean
      editForm: { name: string; enabled: boolean }
      saveEdit(): Promise<void>
    }

    await wrapper.findAll('.info-card button').find((b) => b.text() === '编辑')!.trigger('click')
    expect(vm.editVisible).toBe(true)
    expect(vm.editForm).toEqual({ name: '1号楼配电室 电表01', enabled: true })

    vm.editForm.name = ' 1号楼配电室 电表99 '
    vm.editForm.enabled = false
    await vm.saveEdit()
    await settle()

    expect(deviceApi.update).toHaveBeenCalledWith('dk1', '1号楼配电室 电表99', 'DISABLED')
    expect(vm.editVisible).toBe(false)
    expect(wrapper.find('.info-card h2').text()).toBe('1号楼配电室 电表99')
    expect(wrapper.find('.info-card').text()).toContain('禁用')
    wrapper.unmount()
  })

  it('shows a result page when the device cannot be loaded', async () => {
    vi.mocked(deviceApi.get).mockRejectedValue(new Error('设备不存在: dk1'))
    const { wrapper } = await mountAt('/devices/dk1')

    expect(wrapper.text()).toContain('无法加载设备')
    wrapper.unmount()
  })
})
```

- [ ] **Step 10: Run it to verify it fails**

Run: `cd conduit-ui && npx vitest run src/views/DeviceDetailView.spec.ts ; cd ..`
Expected: FAIL — `.info-card` not found, `LatestValuesPanel` not rendered, `initialProps` undefined.

- [ ] **Step 11: Rewrite the device detail page**

Replace `conduit-ui/src/views/DeviceDetailView.vue` with:

```vue
<script setup lang="ts">
/**
 * 设备详情页：上方设备信息卡（名称、在线与启用状态、所属产品、设备 Key、最后上报、创建日期，「连接参数」弹窗与「编辑」）；
 * 下方「最新值 | 历史」两个标签页，标签与 ?tab= 同步（router.replace，不产生历史记录）。
 * 最新值每 5 秒轮询，交给 LatestValuesPanel 以卡片或表格展示；点击卡片切到历史标签并只选中该测点（?tab=history&props=Ua）。
 * HistoryPanel 只在历史标签激活时挂载，从 ?props= 读取初始选中。设备名称写入面包屑与浏览器标题；
 * 物模型用于判断卡片是数值还是状态量，取不到时按值本身判断。
 */

import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { DeviceView, PointView, PropertyView } from '@/api/types'
import { deviceTabFromQuery, deviceTabQuery, historyQuery, propsFromQuery, type DeviceTab } from '@/utils/deviceTabs'
import { usePolling } from '@/composables/usePolling'
import { useBreadcrumb } from '@/composables/useBreadcrumb'
import DeviceInfoCard from '@/components/DeviceInfoCard.vue'
import ConnectionDialog from '@/components/ConnectionDialog.vue'
import LatestValuesPanel from '@/components/LatestValuesPanel.vue'
import HistoryPanel from '@/components/HistoryPanel.vue'

// 不能叫 key：key 是 Vue 保留属性，不会作为 prop 传入
const props = defineProps<{ deviceKey: string }>()

const route = useRoute()
const router = useRouter()
const { setName } = useBreadcrumb()

// 当前标签与 ?tab= 同步；用 replace 切换，不产生历史记录
const activeTab = computed<DeviceTab>({
  get: () => deviceTabFromQuery(route.query.tab),
  set: (tab) => {
    void router.replace({ query: deviceTabQuery(route.query, tab) })
  },
})
/** 历史标签初始选中的测点（?props=） */
const historyProps = computed(() => propsFromQuery(route.query.props))

const device = ref<DeviceView | null>(null)
const failed = ref(false)
const productName = ref<string | null>(null)
const model = ref<PropertyView[]>([])
const values = ref<PointView[]>([])
const lastRefreshed = ref<number | null>(null)
const connectionVisible = ref(false)
const editVisible = ref(false)
const saving = ref(false)
const editForm = reactive({ name: '', enabled: true })

/**
 * 加载设备，再并行加载所属产品名称与物模型。设备加载失败时提示并显示结果页；
 * 产品名称与物模型只是辅助展示，失败时静默（名称退回产品 Key，卡片按值本身判断类型）。
 */
async function loadDevice() {
  try {
    device.value = await deviceApi.get(props.deviceKey)
    setName(device.value.name)
  } catch (e) {
    failed.value = true
    ElMessage.error(problemMessage(e))
    return
  }
  const productKey = device.value.productKey
  const [product, properties] = await Promise.allSettled([productApi.get(productKey), thingModelApi.list(productKey)])
  if (product.status === 'fulfilled') {
    productName.value = product.value.name
  }
  if (properties.status === 'fulfilled') {
    model.value = properties.value
  }
}

/**
 * 加载最新值并记录刷新时间；用最新的采样时间推进信息卡的「最后上报」。
 * 不处理错误：由轮询吞掉，下一轮继续重试。
 */
async function loadLatest() {
  const res = await deviceApi.latest(props.deviceKey)
  values.value = res.values
  lastRefreshed.value = Date.now()
  const newest = res.values.reduce((max, v) => Math.max(max, v.ts), 0)
  if (device.value && newest > (device.value.lastReportAt ?? 0)) {
    device.value = { ...device.value, lastReportAt: newest }
  }
}

/** 点击指标卡片：切到历史标签并只选中该测点 */
function openHistory(identifier: string) {
  void router.replace({ query: historyQuery(route.query, identifier) })
}

/** 打开编辑对话框，回填当前名称与启用状态 */
function openEdit() {
  if (!device.value) {
    return
  }
  editForm.name = device.value.name
  editForm.enabled = device.value.status === 'ENABLED'
  editVisible.value = true
}

/** 保存名称与启用状态：名称必填；保存中忽略重复点击；成功后更新信息卡、面包屑并关闭对话框 */
async function saveEdit() {
  if (saving.value || !device.value) {
    return
  }
  const name = editForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入设备名称')
    return
  }
  saving.value = true
  try {
    device.value = await deviceApi.update(device.value.deviceKey, name, editForm.enabled ? 'ENABLED' : 'DISABLED')
    setName(device.value.name)
    editVisible.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    saving.value = false
  }
}

onMounted(loadDevice)
usePolling(loadLatest, 5000)
</script>

<template>
  <div v-if="device" class="detail">
    <DeviceInfoCard :device="device" :product-name="productName" @connection="connectionVisible = true" @edit="openEdit" />

    <div class="cd-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="最新值" name="latest">
          <LatestValuesPanel
            v-if="activeTab === 'latest'"
            :device-key="device.deviceKey"
            :values="values"
            :model="model"
            :last-refreshed="lastRefreshed"
            @open-history="openHistory"
          />
        </el-tab-pane>
        <el-tab-pane label="历史" name="history">
          <HistoryPanel
            v-if="activeTab === 'history'"
            :device-key="device.deviceKey"
            :product-key="device.productKey"
            :initial-props="historyProps"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <ConnectionDialog v-model="connectionVisible" :product-key="device.productKey" :device-key="device.deviceKey" />

    <el-dialog v-model="editVisible" title="编辑设备" width="min(440px, 92vw)" :close-on-click-modal="false">
      <el-form label-width="80px" class="edit-form" @submit.prevent="saveEdit">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
  <el-result v-else-if="failed" icon="warning" title="无法加载设备" sub-title="设备可能已被删除，或不属于当前租户。">
    <template #extra>
      <el-button type="primary" @click="router.push({ name: 'devices' })">返回设备列表</el-button>
    </template>
  </el-result>
  <el-skeleton v-else :rows="6" animated />
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-4);
}
</style>
```

- [ ] **Step 12: Run all frontend tests, the type check and the build**

Run: `cd conduit-ui && npm test && npm run build ; cd ..`
Expected: all specs PASS; type check clean; build succeeds.

- [ ] **Step 13: Manual check against a dev server**

With `VITE_API_TARGET=http://<docker-host>:8091 npm run dev`, open a demo device: the info card shows the name, a green 在线 dot, 「启用」, 所属产品 「三相电表（演示）」 (link), a copyable key, 「最后上报 刚刚」 and the creation date; 「连接参数」 opens the dialog. The 最新值 tab shows cards: voltages with values and units and sparklines for the first 8 numeric properties, 「运行状态」 as a 「运行」 tag without a line. Switch to 表格, reload: the table view is kept. Click the 「A 相电压」 card: the URL becomes `?tab=history&props=Ua` and only Ua is selected. Toggle 深色: cards, sparklines and the history charts are readable.

- [ ] **Step 14: Commit**

```bash
git add conduit-ui/src/utils/metricCards.ts conduit-ui/src/utils/metricCards.spec.ts \
  conduit-ui/src/utils/sparkline.ts conduit-ui/src/utils/sparkline.spec.ts \
  conduit-ui/src/utils/deviceTabs.ts conduit-ui/src/utils/deviceTabs.spec.ts \
  conduit-ui/src/utils/historyProperties.ts conduit-ui/src/utils/historyProperties.spec.ts \
  conduit-ui/src/components/Sparkline.vue conduit-ui/src/components/Sparkline.spec.ts \
  conduit-ui/src/components/MetricCard.vue conduit-ui/src/components/MetricCard.spec.ts \
  conduit-ui/src/components/LatestValuesPanel.vue conduit-ui/src/components/LatestValuesPanel.spec.ts \
  conduit-ui/src/components/DeviceInfoCard.vue conduit-ui/src/components/ConnectionDialog.vue \
  conduit-ui/src/components/ConnectionDialog.spec.ts \
  conduit-ui/src/components/HistoryPanel.vue conduit-ui/src/components/HistoryPanel.spec.ts \
  conduit-ui/src/views/DeviceDetailView.vue conduit-ui/src/views/DeviceDetailView.spec.ts
git commit -m "feat(ui): device info card and metric cards with sparklines linked to history"
```

---
## Task 7: Home overview, default route and the login page

**Files:**
- Modify: `conduit-ui/src/api/types.ts` (overview types)
- Create: `conduit-ui/src/api/overview.ts`
- Create: `conduit-ui/src/utils/overview.ts`
- Create: `conduit-ui/src/views/HomeView.vue`
- Modify: `conduit-ui/src/router/meta.ts` (`home`), `conduit-ui/src/router/index.ts` (home route, `/` → `/home`), `conduit-ui/src/layouts/menu.ts` (概览 group), `conduit-ui/src/test/viewTestUtils.ts` (home route)
- Modify: `conduit-ui/src/utils/redirect.ts` (fallback `/home`)
- Modify: `conduit-ui/src/views/LoginView.vue` (full replacement below)
- Test: `conduit-ui/src/utils/overview.spec.ts`, `conduit-ui/src/views/HomeView.spec.ts`, `conduit-ui/src/views/LoginView.spec.ts` (new)
- Test: `conduit-ui/src/utils/redirect.spec.ts` (full replacement below), `conduit-ui/src/layouts/AppLayout.spec.ts` (one new test)

**Interfaces:**
- Consumes: Task 2 JSON of `GET /api/v1/overview`; Task 4 `ROUTE_META`, `MENU_GROUPS`, `PageHeader`, `createTestRouter`; Task 5 `RelativeTime`, `formatCount`; existing `http` (`api/client.ts`), `createVisiblePoller`, `createRequestSeq`, `problemMessage`, `useAuthStore().login`, `safeRedirect`.
- Produces:
  - `api/types.ts`: `DeadSummary { total; byReason: Record<string, number> }`, `RecentDevice { deviceKey; name; productKey: string | null; productName: string | null; lastReportAt: number }`, `ProductStat { productKey; name; deviceCount; onlineCount: number | null }`, `OverviewView { productCount; propertyCount; deviceCount; enabledCount; disabledCount; onlineCount: number | null; deadLastHour: DeadSummary | null; recentDevices; products }`.
  - `api/overview.ts`: `overviewApi.get(): Promise<OverviewView>`.
  - `utils/overview.ts`: `reasonLabel(reason)`, `deadText(dead: DeadSummary | null): string`, `onlineRate(online: number | null, total: number): number | null`.
  - Route `home` (`/home`, meta title 首页, breadcrumb 概览 / 首页, menu `/home`); `/` redirects to `/home`; `safeRedirect` falls back to `/home`.

**Decisions recorded here:**
- Dead-letter reasons are shown with Chinese labels (`FIELD_REJECTED` → 测点被剔除, …); an unknown reason is shown as its code. The summary lists reasons by count descending, ties by code, joined with 「 · 」. `null` → 「暂不可用」 (and the tile number 「—」); 0 → 「无」.
- The online rate is `round(online / deviceCount × 100)`; 0 devices → 0 %; `onlineCount null` hides the progress bar and shows 「在线状态暂不可用」.
- Home refresh uses `createVisiblePoller` (30 s, paused while hidden). A failing refresh shows one error message per failure streak and keeps the last data on screen.
- Login: validation and server errors appear in an `el-alert` inside the form instead of a toast; the login logic (store `login`, `safeRedirect`) is unchanged except the fallback target, which becomes `/home`.
- The login brand panel stays dark in both themes (`--cd-brand-*`); the form side follows the theme.

- [ ] **Step 1: Write the failing specs**

Create `conduit-ui/src/utils/overview.spec.ts`:

```ts
/** overview 工具的单元测试：在线率、死信摘要（按条数排序、中文原因、0 为「无」、不可用） */
import { describe, expect, it } from 'vitest'
import { deadText, onlineRate, reasonLabel } from './overview'

describe('onlineRate', () => {
  it('rounds the percentage and handles empty or unknown values', () => {
    expect(onlineRate(100, 200)).toBe(50)
    expect(onlineRate(1, 3)).toBe(33)
    expect(onlineRate(0, 0)).toBe(0)
    expect(onlineRate(null, 10)).toBeNull()
  })
})

describe('deadText', () => {
  it('summarizes reasons by count with Chinese labels', () => {
    expect(deadText({ total: 5, byReason: { PROCESSING_ERROR: 1, FIELD_REJECTED: 3, MALFORMED_JSON: 1 } })).toBe(
      '测点被剔除 3 · JSON 格式错误 1 · 处理出错 1',
    )
  })

  it('shows 无 for zero and 暂不可用 when unknown', () => {
    expect(deadText({ total: 0, byReason: {} })).toBe('无')
    expect(deadText(null)).toBe('暂不可用')
  })

  it('keeps an unknown reason code as it is', () => {
    expect(reasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(deadText({ total: 2, byReason: { SOMETHING_NEW: 2 } })).toBe('SOMETHING_NEW 2')
  })
})
```

Create `conduit-ui/src/views/HomeView.spec.ts`:

```ts
/** HomeView 组件测试：4 个指标卡的数值与副文、在线相关字段为 null 时显示「—」、两个列表跳转、每 30 秒刷新 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HomeView from './HomeView.vue'
import { overviewApi } from '@/api/overview'
import type { OverviewView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/overview', () => ({ overviewApi: { get: vi.fn() } }))

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)

function overview(patch: Partial<OverviewView> = {}): OverviewView {
  return {
    productCount: 2,
    propertyCount: 15,
    deviceCount: 200,
    enabledCount: 198,
    disabledCount: 2,
    onlineCount: 100,
    deadLastHour: { total: 4, byReason: { FIELD_REJECTED: 3, PROCESSING_ERROR: 1 } },
    recentDevices: [
      { deviceKey: 'dk1', name: '1号楼配电室 电表01', productKey: 'pk1', productName: '三相电表（演示）', lastReportAt: NOW - 5000 },
    ],
    products: [
      { productKey: 'pk1', name: '三相电表（演示）', deviceCount: 200, onlineCount: 100 },
      { productKey: 'pk2', name: '水表（演示）', deviceCount: 0, onlineCount: 0 },
    ],
    ...patch,
  }
}

async function mountView() {
  const router = createTestRouter()
  await router.push('/home')
  await router.isReady()
  const wrapper = mount(HomeView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

const tile = (wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], name: string) =>
  wrapper.find(`[data-test="tile-${name}"]`).text()

describe('HomeView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(NOW)
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    vi.clearAllMocks()
    vi.mocked(overviewApi.get).mockResolvedValue(overview())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the four tiles with their counts and summaries', async () => {
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'products')).toContain('2')
    expect(tile(wrapper, 'products')).toContain('共定义 15 个属性')
    expect(tile(wrapper, 'devices')).toContain('200')
    expect(tile(wrapper, 'devices')).toContain('已启用 198 · 已禁用 2')
    expect(tile(wrapper, 'online')).toContain('100')
    expect(tile(wrapper, 'online')).toContain('在线率 50%')
    expect(tile(wrapper, 'dead')).toContain('4')
    expect(tile(wrapper, 'dead')).toContain('测点被剔除 3 · 处理出错 1')
    wrapper.unmount()
  })

  it('shows — for unknown presence fields and 无 for zero dead letters', async () => {
    vi.mocked(overviewApi.get).mockResolvedValue(
      overview({
        onlineCount: null,
        deadLastHour: null,
        recentDevices: [],
        products: [{ productKey: 'pk1', name: '三相电表（演示）', deviceCount: 200, onlineCount: null }],
      }),
    )
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'online')).toContain('—')
    expect(tile(wrapper, 'online')).toContain('在线状态暂不可用')
    expect(tile(wrapper, 'dead')).toContain('—')
    expect(tile(wrapper, 'dead')).toContain('暂不可用')
    expect(wrapper.find('.by-product').text()).toContain('— / 200')
    expect(wrapper.find('.recent').text()).toContain('暂无上报')
    wrapper.unmount()

    vi.mocked(overviewApi.get).mockResolvedValue(overview({ deadLastHour: { total: 0, byReason: {} } }))
    const second = await mountView()
    expect(tile(second.wrapper, 'dead')).toContain('无')
    second.wrapper.unmount()
  })

  it('opens a recently reported device and a product from the lists', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.recent .row').trigger('click')
    await settle()
    expect(router.currentRoute.value.name).toBe('device-detail')
    expect(router.currentRoute.value.params.deviceKey).toBe('dk1')

    await wrapper.find('.by-product .row').trigger('click')
    await settle()
    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.params.productKey).toBe('pk1')
    wrapper.unmount()
  })

  it('refreshes every 30 seconds and stops on unmount', async () => {
    const { wrapper } = await mountView()
    expect(overviewApi.get).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(overviewApi.get).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(overviewApi.get).toHaveBeenCalledTimes(2)
  })
})
```

Create `conduit-ui/src/views/LoginView.spec.ts`:

```ts
/** LoginView 组件测试：登录成功跳到 redirect 或首页；接口错误与空输入的提示显示在表单内 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from './LoginView.vue'
import { authApi } from '@/api/auth'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn() } }))

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

async function submit(wrapper: Awaited<ReturnType<typeof mountAt>>['wrapper'], username: string, password: string) {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue(username)
  await inputs[1].setValue(password)
  await wrapper.find('form').trigger('submit')
  await settle()
}

describe('LoginView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'token',
      expiresIn: 900,
      user: { username: 'admin', role: 'ADMIN', tenantName: 'Default' },
    })
  })

  it('shows the brand panel and logs in to the redirect target', async () => {
    const { wrapper, router } = await mountAt('/login?redirect=/devices/dk1')

    expect(wrapper.find('.brand').text()).toContain('Conduit 设备接入平台')
    await submit(wrapper, 'admin', 'secret-pass')

    expect(authApi.login).toHaveBeenCalledWith('admin', 'secret-pass')
    expect(router.currentRoute.value.fullPath).toBe('/devices/dk1')
    wrapper.unmount()
  })

  it('goes to the home page without a redirect', async () => {
    const { wrapper, router } = await mountAt('/login')

    await submit(wrapper, 'admin', 'secret-pass')

    expect(router.currentRoute.value.name).toBe('home')
    wrapper.unmount()
  })

  it('shows the server error inside the form', async () => {
    vi.mocked(authApi.login).mockRejectedValue(new Error('用户名或密码错误'))
    const { wrapper, router } = await mountAt('/login')

    await submit(wrapper, 'admin', 'wrong')

    expect(wrapper.find('.login-error').text()).toContain('用户名或密码错误')
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })

  it('asks for both fields without calling the server', async () => {
    const { wrapper } = await mountAt('/login')

    await submit(wrapper, 'admin', '')

    expect(wrapper.find('.login-error').text()).toContain('请输入用户名和密码')
    expect(authApi.login).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
```

Replace `conduit-ui/src/utils/redirect.spec.ts` with:

```ts
/** safeRedirect 的单元测试：只接受站内相对路径，其余回到默认页（首页） */
import { describe, expect, it } from 'vitest'
import { safeRedirect } from './redirect'

describe('safeRedirect', () => {
  it('accepts in-app paths', () => {
    expect(safeRedirect('/devices/abc')).toBe('/devices/abc')
    expect(safeRedirect('/devices?x=1#y')).toBe('/devices?x=1#y')
  })

  it.each(['//evil.com', '/\\evil.com', 'http://evil.com', 'javascript:alert(1)', 'devices', ''])(
    'rejects %s',
    (value) => {
      expect(safeRedirect(value)).toBe('/home')
    },
  )

  it('rejects non-strings', () => {
    expect(safeRedirect(undefined)).toBe('/home')
    expect(safeRedirect(['/devices'])).toBe('/home')
  })

  it('uses a custom fallback', () => {
    expect(safeRedirect('//x', '/products')).toBe('/products')
  })
})
```

In `conduit-ui/src/layouts/AppLayout.spec.ts`, insert before the final `})`:

```ts
  it('shows the 概览 group with 首页 highlighted on the home page', async () => {
    const { wrapper } = await mountAt('/home')

    expect(wrapper.text()).toContain('概览')
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('首页')
    expect(wrapper.findAll('.breadcrumb .el-breadcrumb__inner').map((c) => c.text())).toEqual(['概览', '首页'])
    wrapper.unmount()
  })
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd conduit-ui && npx vitest run src/utils/overview.spec.ts src/views/HomeView.spec.ts src/views/LoginView.spec.ts src/utils/redirect.spec.ts src/layouts/AppLayout.spec.ts ; cd ..`
Expected: FAIL — unresolved `./overview`, `./HomeView.vue`, `@/api/overview`; redirect expects `/home` but gets `/devices`; `/home` resolves to `not-found` in the layout spec; LoginView has no `.brand`/`.login-error`.

- [ ] **Step 3: Add the overview types, API and helpers**

Append to `conduit-ui/src/api/types.ts`:

```ts
/** 死信统计：条数与按原因（服务端 DeadReason 名称）分组的条数 */
export interface DeadSummary {
  total: number
  byReason: Record<string, number>
}

/** 最近上报的设备；lastReportAt 为毫秒 */
export interface RecentDevice {
  deviceKey: string
  name: string
  productKey: string | null
  productName: string | null
  lastReportAt: number
}

/** 单个产品的设备统计；onlineCount 在服务端 Redis 不可用时为 null */
export interface ProductStat {
  productKey: string
  name: string
  deviceCount: number
  onlineCount: number | null
}

/** 首页总览（按当前租户）；onlineCount、deadLastHour 在服务端 Redis 不可用时为 null，recentDevices 此时为空 */
export interface OverviewView {
  productCount: number
  propertyCount: number
  deviceCount: number
  enabledCount: number
  disabledCount: number
  onlineCount: number | null
  deadLastHour: DeadSummary | null
  recentDevices: RecentDevice[]
  products: ProductStat[]
}
```

Create `conduit-ui/src/api/overview.ts`:

```ts
/** 首页总览 REST 调用：GET /api/v1/overview（只读，按当前租户统计；在线相关字段可能为 null）。 */
import { http } from './client'
import type { OverviewView } from './types'

export const overviewApi = {
  get: () => http.get<OverviewView>('/api/v1/overview').then((r) => r.data),
}
```

Create `conduit-ui/src/utils/overview.ts`:

```ts
/** 首页总览的显示文案：死信原因的中文名称与摘要、在线率。 */
import type { DeadSummary } from '@/api/types'

const REASON_LABELS: Record<string, string> = {
  FIELD_REJECTED: '测点被剔除',
  MALFORMED_ENVELOPE: '报文结构错误',
  MALFORMED_JSON: 'JSON 格式错误',
  INVALID_PARAMS: '参数不合法',
  UNKNOWN_DEVICE: '未知设备',
  DEVICE_DISABLED: '设备已禁用',
  PRODUCT_MISMATCH: '产品不匹配',
  TS_OUT_OF_RANGE: '时间戳越界',
  FIELD_TYPE_CONFLICT: '测点类型冲突',
  UNSUPPORTED_TYPE: '不支持的消息类型',
  MAX_DELIVERY_EXCEEDED: '超过投递次数',
  PROCESSING_ERROR: '处理出错',
  UNKNOWN_CODEC: '未知解码器',
}

/** 死信原因的中文名称；未知原因原样显示 */
export function reasonLabel(reason: string): string {
  return REASON_LABELS[reason] ?? reason
}

/**
 * 死信摘要：按条数从多到少（相同时按原因代码）列出「原因 条数」，以「 · 」连接；0 条为「无」；null（服务端读不到死信流）为「暂不可用」。
 */
export function deadText(dead: DeadSummary | null): string {
  if (!dead) {
    return '暂不可用'
  }
  if (dead.total === 0) {
    return '无'
  }
  return Object.entries(dead.byReason)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([reason, count]) => `${reasonLabel(reason)} ${count}`)
    .join(' · ')
}

/**
 * 在线率（整数百分比）：在线数未知时为 null；没有设备时为 0。
 * @param online 在线设备数（可能为 null）
 * @param total 设备总数
 */
export function onlineRate(online: number | null, total: number): number | null {
  if (online === null) {
    return null
  }
  if (total <= 0) {
    return 0
  }
  return Math.round((online / total) * 100)
}
```

- [ ] **Step 4: Add the home route, menu group and redirect fallback**

In `conduit-ui/src/router/meta.ts`, add this entry as the first key of `ROUTE_META` (before `login`):

```ts
  home: { title: '首页', menu: '/home', breadcrumb: [{ label: '概览' }, { label: '首页' }] },
```

In `conduit-ui/src/router/index.ts`:

1. Change `redirect: '/devices',` to `redirect: '/home',`.
2. Insert as the first child of the layout route (before the `products` child):

```ts
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: ROUTE_META.home,
      },
```

In `conduit-ui/src/test/viewTestUtils.ts`, insert after the `login` route:

```ts
      { path: '/home', name: 'home', component: Empty, meta: ROUTE_META.home },
```

In `conduit-ui/src/layouts/menu.ts`, change the icon import to `import { Box, Cpu, House, OfficeBuilding, User } from '@element-plus/icons-vue'` and insert as the first element of `MENU_GROUPS`:

```ts
  {
    title: '概览',
    items: [{ path: '/home', label: '首页', icon: House }],
  },
```

Replace `conduit-ui/src/utils/redirect.ts` with:

```ts
/** 登录后跳转地址的校验：只接受站内相对路径（以单个 / 开头），否则返回 fallback（默认首页），防止开放重定向。 */
export function safeRedirect(value: unknown, fallback = '/home'): string {
  if (typeof value !== 'string' || !value.startsWith('/')) {
    return fallback
  }
  if (value.startsWith('//') || value.startsWith('/\\')) {
    return fallback
  }
  return value
}
```

- [ ] **Step 5: Implement the home page**

Create `conduit-ui/src/views/HomeView.vue`:

```vue
<script setup lang="ts">
/**
 * 首页总览：4 个指标卡（产品、设备、在线设备、最近 1 小时死信）与「最近上报的设备」「按产品统计」两个列表，点击进入详情。
 * 数据来自 GET /api/v1/overview，每 30 秒自动刷新（页面隐藏时暂停）；服务端 Redis 不可用时在线相关数值为 null，显示「—」。
 * 刷新失败时保留上次数据，同一段连续失败只提示一次。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Box, Connection, Cpu, Warning } from '@element-plus/icons-vue'
import { overviewApi } from '@/api/overview'
import { problemMessage } from '@/api/errors'
import type { OverviewView } from '@/api/types'
import { createVisiblePoller } from '@/composables/usePolling'
import { createRequestSeq } from '@/utils/requestSeq'
import { formatCount } from '@/utils/format'
import { deadText, onlineRate } from '@/utils/overview'
import PageHeader from '@/components/PageHeader.vue'
import RelativeTime from '@/components/RelativeTime.vue'

const REFRESH_MS = 30_000

const router = useRouter()
const data = ref<OverviewView | null>(null)
const seq = createRequestSeq()
let errorShown = false

/** 在线率；在线数未知时为 null */
const rate = computed(() => (data.value ? onlineRate(data.value.onlineCount, data.value.deviceCount) : null))

/** 加载总览：只有最后发出的请求能写入结果；失败时保留上次数据，连续失败只提示一次 */
async function load() {
  const id = seq.next()
  try {
    const res = await overviewApi.get()
    if (seq.isLatest(id)) {
      data.value = res
      errorShown = false
    }
  } catch (e) {
    if (seq.isLatest(id) && !errorShown) {
      errorShown = true
      ElMessage.error(problemMessage(e))
    }
  }
}

const poller = createVisiblePoller(load, REFRESH_MS)

/** 进入设备详情 */
function openDevice(deviceKey: string) {
  void router.push({ name: 'device-detail', params: { deviceKey } })
}

/** 进入产品详情 */
function openProduct(productKey: string) {
  void router.push({ name: 'product-detail', params: { productKey } })
}

onMounted(() => poller.start())
onBeforeUnmount(() => {
  poller.dispose()
  seq.next()
})
</script>

<template>
  <div class="home">
    <PageHeader title="首页" subtitle="平台运行概况，每 30 秒自动刷新。" />

    <el-skeleton v-if="!data" :rows="6" animated />
    <template v-else>
      <div class="tiles">
        <div class="tile cd-panel" data-test="tile-products">
          <div class="tile-head"><el-icon><Box /></el-icon>产品</div>
          <div class="tile-value">{{ data.productCount }}</div>
          <div class="tile-sub">共定义 {{ data.propertyCount }} 个属性</div>
        </div>
        <div class="tile cd-panel" data-test="tile-devices">
          <div class="tile-head"><el-icon><Cpu /></el-icon>设备</div>
          <div class="tile-value">{{ data.deviceCount }}</div>
          <div class="tile-sub">已启用 {{ data.enabledCount }} · 已禁用 {{ data.disabledCount }}</div>
        </div>
        <div class="tile cd-panel" data-test="tile-online">
          <div class="tile-head"><el-icon><Connection /></el-icon>在线设备</div>
          <div class="tile-value">{{ formatCount(data.onlineCount) }}</div>
          <el-progress v-if="rate !== null" :percentage="rate" :show-text="false" :stroke-width="6" status="success" />
          <div class="tile-sub">{{ rate === null ? '在线状态暂不可用' : `在线率 ${rate}%` }}</div>
        </div>
        <div class="tile cd-panel" data-test="tile-dead">
          <div class="tile-head"><el-icon><Warning /></el-icon>最近 1 小时死信</div>
          <div class="tile-value">{{ formatCount(data.deadLastHour?.total) }}</div>
          <div class="tile-sub">{{ deadText(data.deadLastHour) }}</div>
        </div>
      </div>

      <div class="lists">
        <div class="list cd-panel recent">
          <div class="list-head">最近上报的设备</div>
          <el-empty v-if="data.recentDevices.length === 0" description="暂无上报" :image-size="64" />
          <ul v-else>
            <li
              v-for="d in data.recentDevices"
              :key="d.deviceKey"
              class="row"
              role="button"
              tabindex="0"
              @click="openDevice(d.deviceKey)"
              @keydown.enter="openDevice(d.deviceKey)"
            >
              <div class="row-main">
                <span class="row-title">{{ d.name }}</span>
                <span class="cd-muted">{{ d.productName ?? d.productKey ?? '—' }}</span>
              </div>
              <RelativeTime :value="d.lastReportAt" class="cd-muted" />
            </li>
          </ul>
        </div>
        <div class="list cd-panel by-product">
          <div class="list-head">按产品统计</div>
          <el-empty v-if="data.products.length === 0" description="还没有产品" :image-size="64">
            <el-button type="primary" @click="router.push({ name: 'products' })">去创建产品</el-button>
          </el-empty>
          <ul v-else>
            <li
              v-for="p in data.products"
              :key="p.productKey"
              class="row"
              role="button"
              tabindex="0"
              @click="openProduct(p.productKey)"
              @keydown.enter="openProduct(p.productKey)"
            >
              <div class="row-main">
                <span class="row-title">{{ p.name }}</span>
                <span class="cd-muted">在线 / 设备</span>
              </div>
              <span class="row-count">{{ formatCount(p.onlineCount) }} / {{ p.deviceCount }}</span>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--cd-space-4);
  margin-bottom: var(--cd-space-4);
}
.tile {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-2);
}
.tile-head {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 13px;
}
.tile-value {
  color: var(--el-text-color-primary);
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tile-sub {
  color: var(--cd-text-muted);
  font-size: 12px;
}
.lists {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--cd-space-4);
}
.list-head {
  margin-bottom: var(--cd-space-2);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.list ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cd-space-3);
  padding: var(--cd-space-2) var(--cd-space-2);
  border-radius: var(--cd-radius-sm);
  cursor: pointer;
}
.row:hover,
.row:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}
.row-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.row-title {
  overflow: hidden;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.row-count {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
```

- [ ] **Step 6: Redesign the login page**

Replace `conduit-ui/src/views/LoginView.vue` with:

```vue
<script setup lang="ts">
/**
 * 登录页：左侧深色品牌区（Logo、「Conduit 设备接入平台」、一句说明、底部装饰折线），右侧登录表单；窄屏时上下排列。
 * 登录成功后跳到 redirect 参数指向的站内页面（经 safeRedirect 校验，防止开放重定向），没有则去首页。
 * 输入校验与接口错误显示在表单内。
 */

import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataLine, Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { problemMessage } from '@/api/errors'
import { safeRedirect } from '@/utils/redirect'

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

/** 提交登录：用户名或密码为空时只在表单内提示、不请求；失败时在表单内显示服务端给出的原因。 */
async function submit() {
  error.value = ''
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="brand">
      <div class="logo">
        <el-icon :size="26"><DataLine /></el-icon>
        <span>Conduit</span>
      </div>
      <div class="pitch">
        <h1>Conduit 设备接入平台</h1>
        <p>设备经 MQTT 接入，数据按物模型校验后入库；在这里管理产品与设备，查看最新值与历史曲线。</p>
      </div>
      <svg class="decor" viewBox="0 0 400 120" preserveAspectRatio="none" aria-hidden="true">
        <polygon class="area" points="0,120 0,90 40,72 80,80 120,44 160,58 200,30 240,62 280,38 320,52 360,20 400,34 400,120" />
        <polyline points="0,90 40,72 80,80 120,44 160,58 200,30 240,62 280,38 320,52 360,20 400,34" />
      </svg>
    </section>

    <section class="form-side">
      <div class="form-box">
        <h2>登录</h2>
        <p class="hint">使用平台账号登录</p>
        <el-form label-position="top" @submit.prevent="submit">
          <el-alert v-if="error" class="login-error" type="error" :title="error" show-icon :closable="false" />
          <el-form-item label="用户名">
            <el-input v-model="form.username" autocomplete="username" :prefix-icon="User" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="current-password"
              :prefix-icon="Lock"
            />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 100vh;
  background: var(--cd-page-bg);
}
.brand {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--cd-space-5);
  padding: 48px;
  overflow: hidden;
  background: var(--cd-brand-bg);
  color: var(--cd-brand-text);
}
.logo {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  font-size: 20px;
  font-weight: 600;
}
.logo .el-icon {
  color: var(--el-color-primary);
}
.pitch h1 {
  margin: 0 0 var(--cd-space-3);
  font-size: 28px;
}
.pitch p {
  max-width: 420px;
  margin: 0;
  color: var(--cd-brand-muted);
  line-height: 1.7;
}
.decor {
  width: 100%;
  height: 120px;
}
.decor polyline {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 2;
}
.decor .area {
  fill: var(--el-color-primary);
  opacity: 0.12;
}
.form-side {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--cd-space-5);
  background: var(--cd-panel-bg);
}
.form-box {
  width: 100%;
  max-width: 360px;
}
.form-box h2 {
  margin: 0;
  color: var(--el-text-color-primary);
}
.hint {
  margin: var(--cd-space-1) 0 var(--cd-space-5);
  color: var(--cd-text-muted);
}
.login-error {
  margin-bottom: var(--cd-space-4);
}
.submit {
  width: 100%;
}
@media (max-width: 819px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .brand {
    padding: var(--cd-space-5);
  }
  .decor {
    height: 60px;
  }
}
</style>
```

- [ ] **Step 7: Run the specs and the whole frontend**

Run: `cd conduit-ui && npx vitest run src/utils/overview.spec.ts src/views/HomeView.spec.ts src/views/LoginView.spec.ts src/utils/redirect.spec.ts src/layouts/AppLayout.spec.ts ; cd ..`
Expected: all PASS.
Run: `cd conduit-ui && npm test && npm run build ; cd ..`
Expected: all specs PASS; type check clean; build succeeds.

- [ ] **Step 8: Manual check against a dev server**

With `VITE_API_TARGET=http://<docker-host>:8091 npm run dev`: log out and back in — the login page shows the dark brand panel on the left and the form on the right (stacked below 820px); a wrong password shows the error inside the form; after login you land on 首页 (`/` also goes there). The four tiles show the product, device, online and dead-letter numbers; 「最近上报的设备」 and 「按产品统计」 open the details. Check both themes.

- [ ] **Step 9: Commit**

```bash
git add conduit-ui/src/api/types.ts conduit-ui/src/api/overview.ts conduit-ui/src/utils/overview.ts \
  conduit-ui/src/utils/overview.spec.ts conduit-ui/src/views/HomeView.vue conduit-ui/src/views/HomeView.spec.ts \
  conduit-ui/src/views/LoginView.vue conduit-ui/src/views/LoginView.spec.ts conduit-ui/src/router \
  conduit-ui/src/layouts/menu.ts conduit-ui/src/layouts/AppLayout.spec.ts conduit-ui/src/test/viewTestUtils.ts \
  conduit-ui/src/utils/redirect.ts conduit-ui/src/utils/redirect.spec.ts
git commit -m "feat(ui): home overview as the default page and a redesigned login page"
```

---
## Task 8: Docs, CLAUDE.md sync, comment check, deploy and acceptance

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md` (§1.1 table, §1.2, §2.2, §4)
- Modify: `docs/backlog-v0.2.md`
- Modify: `docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md` (status line, acceptance record)

**Interfaces:**
- Consumes: everything above. `deploy/build-local.sh` builds from a clean checkout of `HEAD`, so everything must be committed before Step 6.
- Produces: updated docs and `CLAUDE.md`; a rebuilt demo stack on `<docker-host>`; the acceptance record for spec §9.

**Decisions recorded here:**
- Spec §9 items that only a browser can show (theme and sidebar switching, dark readability of charts and dialogs, breadcrumbs and page headers, card click, login page) are listed as **owner visual check** items with exact click paths. Everything with an API equivalent is checked with `curl` first.
- Item 5's "another tenant" uses a temporary tenant and user created in PostgreSQL that reuse the admin's password hash (there is no user API before V0.2-C), then deletes both — the same approach as the V0.2-B acceptance.

- [ ] **Step 1: Update the README**

In `README.md`:
- In the 「功能：」 paragraph, replace the final `。` with `、前端体验优化（首页总览、分组侧栏与面包屑、设备指标卡片与迷你趋势线、暗色模式与侧栏风格切换）。`
- In the 「设计文档：」 line, append ``、`docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md`（前端体验优化）``.
- In the code-structure tree, insert below the `presence/` line:

```
│   ├── overview/                    首页总览接口：按租户统计设备、在线与最近 1 小时死信
```

- In the same tree, change the `conduit-ui` source line to:

```
│   └── src/  api/  stores/  router/  layouts/  views/  components/  composables/  utils/  styles/
```

- [ ] **Step 2: Sync the decisions into CLAUDE.md**

CLAUDE.md's maintenance rule requires every upgrade to sync its decision-type conventions and append to §4. In `CLAUDE.md`:

1. §1.1 table, `conduit-ui` row: replace `前端：Vue 3 + TypeScript + Element Plus + ECharts` with `前端：Vue 3 + TypeScript + Element Plus + ECharts；统一框架（分组可折叠侧栏、面包屑顶栏、PageHeader）、首页总览、暗色模式`.
2. §1.2: insert these two bullets directly after the `**历史查询**（V0.2-B）` bullet:

```markdown
- **首页总览与计数**（界面刷新）：`GET /api/v1/overview` 按当前租户返回产品数、属性总数、设备数（启用/禁用/在线）、最后上报最近的 5 台设备、各产品设备数（在线/总数，按设备数降序）与最近 1 小时死信（`XREVRANGE` 死信流到 1 小时前、最多 1 万条，只统计 `pk` 属于本租户产品的条目，按 `reason` 分组）。`ProductView` 带 `deviceCount`/`onlineCount`，`UserView` 带 `tenantName`。设备计数统一由 `DeviceStats` 计算（PostgreSQL 分组计数 + Redis 在线状态批量读取）；Redis 不可用时在线数、最近上报与死信为 null（列表为空），其余照常，前端显示「—」。本期读出租户全部设备再统计，设备到万级时改增量计数（见 backlog）。
- **前端框架与外观**（界面刷新）：分组侧栏（概览 / 设备接入 / 系统管理，V0.2-C 的用户、租户为禁用项）可折叠、宽度 < 820px 自动折叠，侧栏有浅色/深色两种风格（默认深色）；顶栏为全局面包屑（路由 `meta.breadcrumb`，详情页用 `useBreadcrumb` 设置对象名称）、外观设置、租户名称与用户菜单；浏览器标题「<页面名或对象名称> · Conduit」；`/` 重定向到 `/home`，未知地址显示 404。暗色模式用 Element Plus 暗色变量（`<html>` 的 `dark` 类），主题 浅色/深色/跟随系统（默认跟随系统）；外观偏好（主题、侧栏风格、侧栏折叠、最新值视图）存 `localStorage` 键 `conduit.ui`，读写失败用默认值。ECharts 图表随主题销毁重建（内置 `dark` 主题，背景透明）。设备详情为信息卡 + 指标卡片：前 8 个数值测点一次历史查询画最近 1 小时迷你趋势线（60 秒刷新，数值 5 秒刷新），布尔/枚举显示文字标签；点击卡片进入 `?tab=history&props=<标识>`，历史页从 `props` 读取初始选中。
```

3. §2.2, replace the 「界面」 bullet with:

```markdown
- 界面：中文文案；所有分页表格默认每页 10 条（可选 10/20/50/100），分页、请求序号与越界页码修正统一用 `useTable`；功能入口要显眼（列表有「操作」列按钮、全局面包屑、产品与设备互相可跳转）；详情页的标签页与路由 query `tab` 同步（`router.replace`）。页面顶部用 `PageHeader`，列表筛选用 `FilterBar`，在线状态用 `OnlineDot`，启用/禁用用 `el-tag`，时间用 `RelativeTime`；空状态为 `el-empty` + 引导按钮，首次加载显示骨架屏。样式只用 CSS 变量（`--el-*` 或 `src/styles/tokens.css` 的 `--cd-*`），不写死颜色，深色主题下必须可读。新增页面在 `router/meta.ts` 的 `ROUTE_META` 中登记页面名称、面包屑与菜单归属。
```

4. §4, append:

```markdown
- 2026-10-15 界面刷新：统一框架（分组可折叠侧栏 + 面包屑顶栏 + PageHeader + 404）、暗色模式与侧栏风格（偏好存 `localStorage` 的 `conduit.ui`）、列表套件（`useTable`/`FilterBar`/`OnlineDot`/`RelativeTime`）、设备信息卡与指标卡片迷你趋势线、首页总览 `GET /api/v1/overview`、`ProductView` 设备计数、`UserView` 租户名称（`2026-10-15-conduit-ui-refresh-design.md`）。
```

- [ ] **Step 3: Record the deferred items in the backlog**

Append to `docs/backlog-v0.2.md`:

```markdown

## 前端体验优化（2026-10-15）

- 首页总览与产品列表的在线数、最近上报按"读出租户全部设备 + Redis 批量读取"统计；设备到 1 万台规模时改为增量计数（例如按产品维护在线计数或在线集合，在上下线事件中更新）。
- 最近 1 小时死信按死信流倒序扫描、最多 1 万条（全平台共用一条死信流），超过时计数封顶；需要精确统计时改为按租户/产品分流或写入计数器。
- 产品列表没有关键字筛选（`GET /api/v1/products` 无 keyword 参数），产品变多时再加。
```

- [ ] **Step 4: Comment check over every file this plan touched**

Run from the repository root. `BASE` is the commit that added this plan:

```bash
BASE=$(git log --format=%H -1 -- docs/superpowers/plans/2026-10-15-conduit-ui-refresh.md)
missing=0
for f in $(git diff --name-only --diff-filter=AM "$BASE" HEAD -- '*.java' '*.ts' '*.vue' '*.css'); do
  out=""
  case "$f" in
    *.d.ts) continue ;;
    *.java)
      # 每个 class/interface/record/enum 声明（跳过注解与空行）之前一行必须是 Javadoc 结尾 */
      out=$(awk '
        /^[[:space:]]*$/ { next }
        /^[[:space:]]*@[A-Za-z]/ { next }
        /^[[:space:]]*((public|protected|private|static|final|abstract|sealed)[[:space:]]+)*(class|interface|record|enum)[[:space:]]+[A-Z]/ {
          if (prev !~ /\*\/[[:space:]]*$/) print FILENAME ":" FNR ": missing type Javadoc: " $0
        }
        { prev = $0 }' "$f") ;;
    *.ts)
      # 文件头注释 + 每个导出函数前有 JSDoc
      out=$(awk '
        NF && !seen { seen = 1; if ($0 !~ /^[[:space:]]*(\/\*\*|\/\/)/) print FILENAME ": missing header comment" }
        /^export (async )?function/ { if (prev !~ /\*\/[[:space:]]*$/) print FILENAME ":" FNR ": missing JSDoc: " $0 }
        NF { prev = $0 }' "$f") ;;
    *.vue)
      out=$(awk '
        found && NF { if ($0 !~ /^[[:space:]]*(\/\*\*|\/\/)/) print FILENAME ": missing comment at top of <script setup>"; exit }
        /<script setup/ { found = 1 }' "$f") ;;
    *.css)
      out=$(awk 'NF { if ($0 !~ /^[[:space:]]*\/\*/) print FILENAME ": missing header comment"; exit }' "$f") ;;
  esac
  if [ -n "$out" ]; then echo "$out"; missing=1; fi
done
[ "$missing" = 0 ] && echo "comment check OK"
```

Expected: `comment check OK`. Fix every reported file by adding the Chinese header or JSDoc the owner rule requires, then re-run. The script cannot judge method comments in Java or the content of comments. Also review `git diff "$BASE" HEAD -- '*.java' '*.ts' '*.vue'` and confirm that every public/service/controller/policy/branching method has a Chinese Javadoc/JSDoc stating purpose, key parameters, return value and non-obvious behavior, and that no comment still describes old behavior (for example 「去设备列表」 in the login page or 「两个标签页」 texts that changed).

- [ ] **Step 5: Full verification and docs commit**

```bash
mvn -B verify
cd conduit-ui && npm test && npm run build ; cd ..
git add README.md CLAUDE.md docs/backlog-v0.2.md
git commit -m "docs: describe the frontend refresh and sync its decisions into CLAUDE.md"
```

Expected: BUILD SUCCESS; all frontend specs pass; the build succeeds; the commit is created.

- [ ] **Step 6: Deploy to the demo environment**

```bash
DOCKER="$DOCKER_CLI" COMPOSE="$COMPOSE_CLI" deploy/build-local.sh
$COMPOSE_CLI -f deploy/docker-compose.yml --profile demo ps
```

Expected: the script finishes and every service is `running`/`healthy`. The script runs `npm ci` locally, never on the Docker host. If an image pull on the host is slow, wait.

- [ ] **Step 7: Prepare the shell for the API checks**

Values come from `deploy/.env`; nothing is written to files. `json` evaluates a JavaScript expression against the JSON on stdin (the argument starts with `.` or `[`):

```bash
json() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const v=JSON.parse(s);const r=eval("v"+process.argv[1]);console.log(typeof r==="object"?JSON.stringify(r):r)})' "$1"; }
API=http://<docker-host>:8091
WEB=http://<docker-host>:8090
ADMIN_USER=$(grep '^CONDUIT_ADMIN_USERNAME=' deploy/.env | cut -d= -f2-)
ADMIN_PASS=$(grep '^CONDUIT_ADMIN_PASSWORD=' deploy/.env | cut -d= -f2-)
TOKEN=$(curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | json '.accessToken')
AUTH="Authorization: Bearer $TOKEN"
PSQL() { $COMPOSE_CLI -f deploy/docker-compose.yml exec -T postgres psql -U conduit -d conduit -v ON_ERROR_STOP=1 -qAt -c "$1"; }
DPK=$(curl -s -H "$AUTH" "$API/api/v1/products?size=100" | json '.items.find(p=>p.name==="三相电表（演示）").productKey')
DK=$(curl -s -H "$AUTH" "$API/api/v1/devices?productKey=$DPK&size=1" | json '.items[0].deviceKey')
NOW=$(($(date +%s)*1000))
# 设备列表中在线设备的数量（逐页累加，每页 100 条）；参数为附加的查询条件
online_count() {
  local total pages
  total=$(curl -s -H "$AUTH" "$API/api/v1/devices?size=1$1" | json '.total')
  pages=$(( (total + 99) / 100 ))
  for p in $(seq 1 "$pages"); do
    curl -s -H "$AUTH" "$API/api/v1/devices?page=$p&size=100$1" | json '.items.filter(d=>d.online).length'
  done | awk '{s+=$1} END {print s+0}'
}
```

- [ ] **Step 8: Acceptance 5 — overview numbers match the device list; the user view carries the tenant**

```bash
OV=$(curl -s -H "$AUTH" "$API/api/v1/overview")
for f in productCount propertyCount deviceCount enabledCount disabledCount onlineCount deadLastHour; do echo "$f=$(echo "$OV" | json ".$f")"; done
echo "devices.total=$(curl -s -H "$AUTH" "$API/api/v1/devices?size=1" | json '.total')"
echo "products.total=$(curl -s -H "$AUTH" "$API/api/v1/products?size=1" | json '.total')"
echo "online(list)=$(online_count '')"
echo "$OV" | json '.recentDevices.map(d=>d.name+" "+d.productName+" "+new Date(d.lastReportAt).toISOString()).join("\n")'
echo "$OV" | json '.products.map(p=>p.name+" "+p.onlineCount+"/"+p.deviceCount).join("\n")'
curl -s -H "$AUTH" "$API/api/v1/auth/me" | json '.tenantName'
```

Expected: `deviceCount` = `devices.total`; `productCount` = `products.total`; `onlineCount` = `online(list)` (if a device connects or disconnects between the calls, re-run); `enabledCount + disabledCount = deviceCount`; `deadLastHour` is an object (not `null`); `recentDevices` has at most 5 entries, newest first; `tenantName` is the default tenant's name.

Another tenant sees only its own data:

```bash
PSQL "insert into tenant (code, name) values ('acceptance-other', '验收用其他租户') on conflict (code) do nothing"
PSQL "insert into app_user (tenant_id, username, password_hash, role) select t.id, 'acceptance-other', u.password_hash, u.role from tenant t, app_user u where t.code = 'acceptance-other' and u.username = '$ADMIN_USER' on conflict (username) do nothing"
OTHER=$(curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"acceptance-other\",\"password\":\"$ADMIN_PASS\"}" | json '.accessToken')
curl -s -H "Authorization: Bearer $OTHER" "$API/api/v1/overview"; echo
curl -s -H "Authorization: Bearer $OTHER" "$API/api/v1/auth/me"; echo
PSQL "delete from app_user where username = 'acceptance-other'"
PSQL "delete from tenant where code = 'acceptance-other'"
```

Expected: the overview is all zeros with empty lists (`productCount 0`, `deviceCount 0`, `onlineCount 0`, `deadLastHour.total 0`); `/me` shows `"tenantName":"验收用其他租户"`. The temporary user and tenant are removed.

- [ ] **Step 9: Acceptance 3 — product list counts and paging**

```bash
curl -s -H "$AUTH" "$API/api/v1/products" | json '.size'
curl -s -H "$AUTH" "$API/api/v1/products?size=100" | json '.items.map(p=>p.name+" "+p.onlineCount+"/"+p.deviceCount).join("\n")'
echo "demo total=$(curl -s -H "$AUTH" "$API/api/v1/devices?productKey=$DPK&size=1" | json '.total') online=$(online_count "&productKey=$DPK")"
curl -s -H "$AUTH" "$API/api/v1/devices" | json '.size'
```

Expected: both default sizes are `10`; the 三相电表（演示） line equals `online/total` from the device list.

**Owner visual check** (`$WEB/products`, `$WEB/devices`): the 「设备（在线/总数）」 column shows the same numbers; the device filter bar queries on Enter and 「重置」 clears it; a keyword like `不存在的设备` shows 「没有符合条件的设备」 with 「重置筛选」; 在线 shows green/grey dots; 最后上报 reads 「刚刚」/「N 分钟前」 and hovering shows the full time; every table pages at 10 by default.

- [ ] **Step 10: Acceptance 4 — device detail cards and sparklines**

```bash
IDS=$(curl -s -H "$AUTH" "$API/api/v1/products/$DPK/properties" | json '.filter(p=>p.dataType==="DOUBLE"||p.dataType==="INT").slice(0,8).map(p=>p.identifier).join(",")')
echo "$IDS"
curl -s -H "$AUTH" "$API/api/v1/devices/$DK/history?properties=$IDS&from=$((NOW-3600000))&to=$NOW" \
  | json '.series.map(s=>s.identifier+" "+s.mode+" n="+s.points.length).join("\n")'
curl -s -H "$AUTH" "$API/api/v1/devices/$DK/latest" | json '.values.filter(v=>v.identifier==="st").map(v=>v.enumLabel).join()'
```

Expected: at most 8 identifiers, all numeric; every series has points (about 360 `RAW` points for the last hour at the default 10 s interval); `st` has an `enumLabel` such as `运行`.

**Owner visual check** (`$WEB/devices/<DK>`): the info card shows name, 在线 dot, 「启用」, product link, copyable key, 「最后上报 刚刚」, the creation date, 「连接参数」 (dialog) and 「编辑」; the 最新值 tab shows cards with values, units and sparklines for the identifiers printed above, and 「运行状态」 as a 「运行」 tag without a line; 卡片/表格 switch survives a reload; clicking the 「A 相电压」 card changes the address to `?tab=history&props=Ua` and the 历史 tab selects only Ua.

- [ ] **Step 11: Acceptance 1, 2 and 6 — owner visual checks**

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$WEB/no/such/page"
curl -s "$WEB/no/such/page" | grep -c '<div id="app">'
```

Expected: `200` and `1` (nginx serves the SPA for unknown paths; the router shows the 404 page).

**Owner visual check** in a browser at `$WEB`:
1. The appearance button (brush) switches 侧栏 浅色/深色 and 主题 浅色/深色/跟随系统; 「收起菜单」 collapses the sidebar; after a reload all three are kept (`localStorage` key `conduit.ui`). With 跟随系统, changing the OS theme switches the page live. In 深色, every page is readable: lists, product detail tabs, the product and device dialogs, the 连接参数 dialog, the history charts and the sparklines.
2. Every page (首页, 产品, 产品详情, 设备, 设备详情) has the breadcrumb and a page header; detail pages end with the object name and the browser tab reads `<名称> · Conduit`; `/no/such/page` shows 「页面不存在」 with 「回到首页」; `/` opens 首页.
3. 首页 shows the four tiles and two lists with the numbers from Step 8.
4. The login page (log out first) shows the dark brand panel and the form, readable in both themes; a wrong password shows the error inside the form.
5. At a 375 px wide window (browser dev tools), no page scrolls horizontally and the sidebar is collapsed.

- [ ] **Step 12: Mark the spec implemented and record acceptance**

In `docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md`, change `- 状态：已评审，待实现` to `- 状态：已实现（实施计划 docs/superpowers/plans/2026-10-15-conduit-ui-refresh.md）`, and append this section, filled in with what Steps 8–11 showed:

```markdown
## 11. 验收记录

- 日期与环境：（验收当天的日期），演示环境，`deploy/build-local.sh` 部署。
- 接口核对：总览 deviceCount=（值）与设备列表 total 一致，onlineCount=（值）与设备列表在线数一致；产品列表三相电表（演示）为（在线/总数）；另一租户总览全为 0，`/me` 租户名称正确。
- 1～6：（逐项写「通过」或实际现象；浏览器项由所有者目视确认）。
```

Replace every parenthesized item with the observed value before committing. No parentheses-only text may remain.

- [ ] **Step 13: Commit**

```bash
git add docs/superpowers/specs/2026-10-15-conduit-ui-refresh-design.md
git commit -m "docs: mark the frontend refresh implemented and record acceptance"
```

---

## Self-Review Notes

**Spec coverage** (spec section → task):
- §1 scope (framework, list conventions, device detail, home, login, dark mode, sidebar style; narrow screens without horizontal scroll) → Tasks 3–7; narrow-screen check in Task 8 Step 11.
- §2.1 sidebar: logo, groups with icons, disabled V0.2-C items, collapse with hover names, auto-collapse < 820 px, light/dark styles (default dark), highlight by route ownership → Task 4 (`menu.ts`, `AppLayout`, `meta.menu`); 概览 group in Task 7.
- §2.2 top bar: breadcrumb from `meta.breadcrumb` + `useBreadcrumb` object name with clickable parents; appearance button; tenant tag; avatar dropdown (username, tenant, logout) → Task 4 (+ `tenantName` from Task 1).
- §2.3 dark mode and preferences: Element Plus dark CSS + `dark` class, follow system live, CSS variables only, ECharts follows theme, `localStorage` `conduit.ui` with safe fallback, one primary blue, spacing/radius tokens → Task 3 (charts: `HistoryPanel` in Task 3, `Sparkline` in Task 6).
- §2.4 PageHeader on every page, document title, 404, `/` → `/home` → Tasks 4 and 7 (device detail uses the info card as its header, spec §4).
- §3 lists: `FilterBar` (Enter queries, wraps), `useTable` (10/page, loading, sequence, delete clamp) on products, devices and both product-detail tables, `OnlineDot`, enabled `el-tag`, `RelativeTime` (hover, per-minute), empty states with guide buttons, skeleton on first load, product device column → Task 5.
- §4 device detail: info card, connection dialog, edit; cards with decimals/unit/sparkline/relative time; BOOL/ENUM labels; one history call for ≤ 8 numeric properties over 1 h every 60 s; card/table switch stored in preferences; card click → `tab=history&props=…`; history reads `props` dropping unknown ones → Task 6.
- §5.1 `/api/v1/overview` (shape, per tenant, grouped PG counts, presence and latest times from Redis, 5 recent devices, dead letters via `XREVRANGE` 1 h / 10 000 filtered by tenant `pk`, grouped by `reason`, Redis down → null) → Tasks 1–2; backlog note → Task 8 Step 3.
- §5.2 `ProductView.deviceCount/onlineCount` (list and detail) → Task 1.
- §5.3 home layout and 30 s refresh paused when hidden → Task 7.
- §6 login page (two columns, stacked when narrow, logic unchanged, errors inside the form) → Task 7.
- §7 code structure → File Structure table; all files named in §7 exist (`tokens.css`, `stores/ui.ts`, `useTable`, `useBreadcrumb`, `useTheme`, `PageHeader`, `FilterBar`, `OnlineDot`, `RelativeTime`, `MetricCard`, `Sparkline`, `AppearancePanel`, `AppLayout`, `HomeView`, `NotFoundView`, `overview` package).
- §8 tests: `OverviewServiceTest` (tenant filter, grouping, Redis null) and `OverviewApiIT` (two tenants) → Task 2; `ProductApiIT` counts → Task 1; ui store, `useTheme`, `useTable`, `RelativeTime`, `MetricCard`, `AppLayout`, breadcrumb, `HomeView` (counts, `null` → 「—」), device-detail `props` → Tasks 3–7.
- §9 acceptance → Task 8 Steps 8–11. §10 docs → Task 8 Steps 1–3, 12. Owner rules (comments, CLAUDE.md sync) → Global Constraints, Task 8 Steps 2 and 4.

**Placeholder scan:** no "TBD"/"TODO"/"similar to Task N"; every code step carries complete code or an exact before/after edit. The only fill-in values are the measurements in the Task 8 Step 12 acceptance record, which can only be known at execution time.

**Type consistency:**
- Backend:
  - `DeviceMapper.ProductStatusCount(long, String, int)`, `countByProductAndStatus(Collection<Long>)` and `selectBrief(Collection<Long>)` match `DeviceStats` and `DeviceStatsTest`.
  - `DeviceStats.ProductCounts(int, int, Integer)` and `Snapshot(List<Device>, Map<Long, ProductCounts>, Set<String>)` match `ProductDtos.ProductView.of`, `ProductService` and `OverviewService` and their tests.
  - `DeadLetterReader.since(long, int)` matches `OverviewService` and `OverviewServiceTest`.
  - `OverviewDtos` records match the IT JSON paths and the TS `OverviewView`, `DeadSummary`, `RecentDevice` and `ProductStat`.
  - `UserView(username, role, tenantName)` matches TS `UserView`.
- Frontend:
  - `useUiStore` actions are used with the same names by `AppearancePanel`, `AppLayout` and `LatestValuesPanel`.
  - `useTheme`/`chartTheme`/`applyTheme` names match between Task 3 and the Task 6 `Sparkline`.
  - `ROUTE_META` keys match the route names in the router and in `createTestRouter`.
  - `useTable` returns `onSizeChange`/`onPageChange`, which the views bind.
  - `SparkPoint`, `MetricCardModel`, `historyQuery`, `propsFromQuery`, `initialSelection` and `HistoryPanel.initialProps` match between helpers, components and specs.

**Resolved ambiguities:**
- The top bar needs the tenant name: `UserView.tenantName` is added (no new endpoint).
- `ProductView.onlineCount` is `null` when Redis is down, the same as the overview.
- "Presence-related fields" means `onlineCount`, `products[].onlineCount`, `recentDevices` (`[]`) and `deadLastHour` (`null`). Each Redis read degrades separately.
- Dead letters without `pk` are not counted; a missing `reason` counts as `UNKNOWN`; the 1 h cutoff is by stream ID.
- `products` in the overview lists every product, sorted by device count.
- The primary blue stays Element's default (no override).
- Narrow screens force the sidebar collapsed without touching the saved preference.
- The 404 page lives inside the layout.
- `HistoryPanel` gets `initialProps` instead of reading the router.
- `props` is dropped when leaving the history tab.
- Card click uses `replace`.
- BOOL cards read 是/否.
- The product list has no keyword filter.
- The device list's enable switch becomes a tag plus an action button.
- Device detail edit covers name and enabled state only.
- The login fallback is `/home`.
