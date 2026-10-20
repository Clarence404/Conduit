# Conduit V0.2-R3 Telemetry Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A platform admin can re-feed the raw messages still held in the Redis up-stream into InfluxDB for a chosen time range, so that telemetry lost in an InfluxDB power cut comes back, with progress, cancel and a result that never pollutes presence or the dead-letter stream.

**Architecture:** The stage「decode → thing-model validate → write InfluxDB → write latest value」moves out of `PipelineProcessor` into a new `TelemetryIngestor`, and the in-place backoff retry moves into a new `Backoff`. Normal consumption keeps its behaviour (dead letters, ACK, presence) by composing those two; replay composes the same two and simply never calls presence, `bus.recordDead` or `bus.ack`. `MessageBus` gains an `XRANGE`-by-id-range read, because a Redis stream entry id starts with its millisecond timestamp, so a time range is an id range. `ReplayService` keeps one task in memory (`AtomicReference<ReplayTask>`), runs it on a virtual thread in batches of 500, checks a cancel flag between batches, and exposes `POST` / `GET` / `DELETE` on `/api/v1/platform/telemetry/replay`, which `SecurityConfig` already restricts to platform admins. A new Vue page「数据重放」submits a range, polls every 2 s and can cancel.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data Redis (Lettuce), MyBatis-Plus, Flyway, Micrometer, InfluxDB 3 Core 3.12.0, JUnit 5, AssertJ, Mockito, Awaitility, Testcontainers; Vue 3 + TypeScript + Element Plus + Vitest + `@vue/test-utils`.

**Governing spec:** `docs/superpowers/specs/2026-10-20-conduit-telemetry-replay-design.md` (binding contract); repo-root `CLAUDE.md` (comment rules §2.1, conventions §2.2, dev mode §3, maintenance rule).

## Global Constraints

**Operator-provided placeholders.** The owner sets these in their shell. Never write their real values into any committed file.

| Placeholder | Meaning |
|---|---|
| `$JAVA21_HOME` | Path of a JDK 21 installation |
| `<docker-host>` | Address of the Docker host that runs Testcontainers |
| `<dev-host>` | Address at which the Docker host reaches this development machine (`E2E_DEV_HOST`) |
| `$SYSADMIN_PASSWORD` | Password of the platform admin `sysadmin` (Task 6 acceptance only) |

**Shell preamble.** Run once per shell before any Maven command. All commands run from the repository root.

```bash
export JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH"
export DOCKER_HOST="tcp://<docker-host>:2375" TESTCONTAINERS_HOST_OVERRIDE="<docker-host>"
```

**Command patterns.** The tasks use these verbatim:

- Server unit tests: `mvn -B -q -pl conduit-server -am test -Dtest=<Class>[,<Class>] -Dsurefire.failIfNoSpecifiedTests=false`
- Server integration tests: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test=<Class>[,<Class>] -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
- Common integration tests: `mvn -B -pl conduit-common verify -Dtest=NONE -Dit.test=<Class> -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
- Frontend one spec: `cd conduit-ui && npm run test -- <path/to/file.spec.ts>`
- Frontend whole suite (Task 6 only): `cd conduit-ui && npm run test` and `npm run typecheck`
- Full backend build (Task 6 only): `mvn -B verify`
- e2e run (Task 6 only): `mvn -B -DskipTests install` then `E2E_DEV_HOST=<dev-host> mvn -B verify -Pe2e -pl conduit-e2e`

**Spec values (copied verbatim from the spec, binding for every task):**

- Endpoints `POST` / `GET` / `DELETE` on `/api/v1/platform/telemetry/replay`. Body of `POST`: `{"from": <毫秒>, "to": <毫秒>}`. `GET` returns `{"state": "NONE"}` when nothing has ever run. `DELETE` returns 409 when no task is running.
- Validation: `from` < `to`; both within 0–253402300799999; span at most **6 hours**; otherwise 400.
- Only one task at a time; `POST` while one runs returns 409. Query and cancel carry no task id.
- Task state lives in memory in a single `AtomicReference`, is not persisted, and the last finished task's state stays until the next submission.
- Range read is half-open: `from` inclusive, `to` exclusive (「`to` 当毫秒本身不含在内」), matching the device-list time filters.
- Batch size 500; the cancel flag is checked between batches; a cancelled task keeps the statistics it already gathered and ends in `CANCELLED`.
- Replay **must not** update presence and **must not** write dead letters — rejected messages are counted only.
- Status fields: `state` (`RUNNING | SUCCEEDED | FAILED | CANCELLED | NONE`), `from`, `to`, `startedAt`, `finishedAt`, `scanned`, `written`, `skipped`, `rejected`, `error`. `scanned` = entries read from the stream; `written` = property messages written to the time-series database; `skipped` = decode failures, deleted products, non-property messages; `rejected` = messages whose fields were all rejected by the thing model (counted, never dead-lettered).
- Recoverable errors reuse the pipeline's existing backoff; exceeding the retry limit puts the task into `FAILED` with a reason.
- Frontend route meta, copied verbatim into `ROUTE_META`:

  ```ts
  'telemetry-replay': {
    title: '数据重放',
    menu: '/telemetry-replay',
    scope: 'platform',
    breadcrumb: [{ label: '系统管理' }, { label: '数据重放' }],
  }
  ```

- Frontend page: `PageHeader` with a subtitle stating purpose and boundaries; a required `el-date-picker` `datetimerange`; a 6-hour check done in the browser before the request; a confirmation listing the chosen range; a current-task card with state tag, range, the four counters and elapsed time; a cancel button (with confirmation) while running; polling every 2 s that stops when the task ends and whose timer is cleared when the page is left; `el-empty` with guidance when nothing has run.

**Project rules:**

- **Code comments (CLAUDE.md §2.1; applies to every code block in this plan):**
  1. Every class, interface, record and enum gets a Chinese header comment as Javadoc on the type — nested types and test classes included.
  2. Every key method gets Chinese Javadoc: what it does, important parameters and return value, and non-obvious behaviour (exceptions, edge cases, side effects). "Key" means public, service and lifecycle methods, pure policy functions and anything with branching. Trivial getters and one-line helpers are exempt.
  3. Comments say "what and why", never restate the code, and change together with the code.
  4. Do not edit `CLAUDE.md` except in Task 6, which its maintenance rule requires.
- **UI conventions (CLAUDE.md §2.2):** Chinese copy; `PageHeader` at the top of the page; colours only through CSS variables (`--el-*` or `--cd-*` from `src/styles/tokens.css`), readable in dark mode; every new page registered in `ROUTE_META` with `scope`; write buttons shown by `usePermission().canWrite` — this page is platform-only, so its buttons are gated by the route's `scope: 'platform'` instead, and no tenant role can reach it. Tables are not used here, so the 10-rows-per-page rule and `useTable` do not apply; the plan says so explicitly rather than inventing a table.
- **Logging (CLAUDE.md §2.2):** SLF4J parameterized. One INFO line when a replay starts, is cancelled and finishes (management operation); per-batch detail is DEBUG. Never log device secrets, passwords, JWTs, the internal token or `Authorization`; payload values only at TRACE, truncated to 256 characters.
- **Git:** Conventional Commits. This repo commits with the author name only and an empty email (`.git/config` already holds them). Never run `git config user.email` and never pass an email. No AI attribution lines: no `Co-Authored-By`, no "Generated with".
- **No secrets in tracked files:** no IP addresses, host names, local Windows paths, real user names, real passwords or tokens. Docs use `<docker-host>` / `<dev-host>`.
- **No deploy steps** (CLAUDE.md §3). Nothing here runs `deploy/package.sh` or starts the stack on the Docker host. Acceptance in Task 6 is tests plus API checks against the stack the owner runs from the IDE (`Conduit (local all)`).
- **No new Maven or npm dependency.** Everything used already exists in the build.
- **Tests:** `*Test` is a unit test (surefire), `*IT` an integration test (failsafe, Testcontainers). Each task runs only the test classes it names; the full `mvn -B verify`, the frontend suite and the e2e run happen only in Task 6.
- **Model split:** implementation subagents use Sonnet. Every code block here is complete and copyable — implementers copy it and do not design it. If a step does not compile, or a named test fails for a reason the step does not explain, stop and report. Do not improvise.

**Facts about the existing code this plan relies on** (verified against `3298982`):

| Fact | Where |
|---|---|
| `PipelineProcessor.process(List<Delivery>)` splits a batch, calls `toPending` per property message, acks presence messages, then batch-writes properties; `retry(...)` is a private unlimited exponential backoff with `isRecoverable` walking the cause chain (depth 16) | `conduit-server/.../pipeline/PipelineProcessor.java` |
| `ProcessingAbortedException` is a package-private nested class of `PipelineProcessor`, referenced once in tests (`PipelineProcessorTest:225`) | same, `PipelineProcessorTest` |
| `PipelineProcessor` has a package-private test constructor `(bus, props, identities, thingModels, decoders, presenceHandler, writer, latest, presence, meters, initialBackoff, maxBackoff)` used by `PipelineProcessorTest` | same |
| `MessageBus` exposes `publish`, `ensureGroup`, `read`, `claimStale`, `ack`, `deadLetter`, `recordDead` — **no range read**; the only implementation is `RedisStreamMessageBus`, and tests use Mockito mocks, so adding an interface method breaks no fake | `conduit-common/.../bus/` |
| `MessageBusProperties(streamKey="conduit:stream:up", deadKey="conduit:stream:dead", maxLen=1000000, deadMaxLen=100000)` | `bus/MessageBusProperties.java` |
| `Delivery(String id, Map<String,String> fields, long deliveryCount)`; `delivery.message()` throws `IllegalArgumentException` on a broken envelope | `bus/Delivery.java` |
| `PresenceStore.applyDataBatch(Map<String,Long>)` is the only call that marks devices online from data, and it is made by `PipelineProcessor` after ACK | `presence/PresenceStore.java` |
| `LatestValueStore.update(deviceKey, ts, fields)` compares timestamps per field in Lua, so replaying old data never overwrites newer values | `telemetry/LatestValueStore.java` |
| `TelemetryWriter.write(List<TelemetryPoint>)` returns `WriteResult` whose `rejected()` holds `WriteResult.Rejected(int index, String message)`; it throws `TelemetryUnavailableException` (recoverable) or `IllegalStateException` (not) | `telemetry/TelemetryWriter.java` |
| `SecurityConfig` maps `/api/v1/platform/**` to `hasRole(PLATFORM_ADMIN)`, and an impersonation token's effective role is `TENANT_ADMIN`, so impersonating admins are refused too | `auth/SecurityConfig.java` |
| Controllers signal HTTP status through `ApiException.badRequest/conflict/notFound`, turned into RFC 7807 `problem+json` by `GlobalExceptionHandler` | `web/ApiException.java`, `web/GlobalExceptionHandler.java` |
| The device-list filters cap millisecond parameters at `DeviceFilter.MAX_MILLIS = 253402300799999L` and reject `from >= to` with 400 | `device/DeviceFilter.java` |
| A `Clock` bean exists (`Clock.systemUTC()`) and is injected where time is needed | `config/ClockConfig.java` |
| The test profile sets `conduit.pipeline.enabled: false`, `conduit.bus.stream-key: test:up`, `conduit.bus.dead-key: test:dead`; `IntegrationTestBase` offers `createDevice`, `defineProperty`, `insertProperty`, `loginAsPlatformAdmin`, `login`, `createTenant`, `createUser`, `unique`, `jdbc`, `redis`, `mvc` | `src/test/resources/application-test.yml`, `support/IntegrationTestBase.java` |
| `InfluxTestSupport.count/awaitCount/fieldType` query the `property` table for one device and field | `support/InfluxTestSupport.java` |
| Frontend: `ROUTE_META` in `router/meta.ts` is shared by the real router and `test/viewTestUtils.ts`; `menuGroupsFor` in `layouts/menu.ts` builds the sidebar; `startPolling(task, intervalMs)` in `composables/usePolling.ts` returns a stop function; `problemMessage(error)` in `api/errors.ts` renders a backend error | `conduit-ui/src/` |

---

## File Structure

**conduit-common**

| File | Responsibility | Task |
|---|---|---|
| `bus/MessageBus.java` (modify) | Adds `range(startId, endId, count)` to the bus contract | 2 |
| `bus/RedisStreamMessageBus.java` (modify) | `XRANGE` implementation, ids returned as `Delivery` with `deliveryCount = 0` | 2 |
| test `bus/RedisStreamMessageBusIT.java` (modify) | Range read: bounds, limit, no consumption | 2 |

**conduit-server**

| File | Responsibility | Task |
|---|---|---|
| `pipeline/ProcessingAbortedException.java` (new) | Promoted from a nested class so `Backoff` can throw it | 1 |
| `pipeline/Backoff.java` (new) | In-place exponential backoff, unlimited for the pipeline, capped for replay; `isRecoverable` | 1 |
| `pipeline/TelemetryIngestor.java` (new) | The shared unit: decode → validate → write InfluxDB → write latest value. No dead letters, no ACK, no presence | 1 |
| `pipeline/PipelineProcessor.java` (rewrite) | Batch dispatch, dead letters, ACK and presence only; delegates the rest | 1 |
| test `pipeline/BackoffTest.java` (new) | Retry policy | 1 |
| test `pipeline/TelemetryIngestorTest.java` (new) | Prepare/validate/write/latest contract | 1 |
| test `pipeline/PipelineProcessorTest.java` (modify, 1 line) | Exception import after the promotion | 1 |
| `replay/ReplayState.java` (new) | `NONE / RUNNING / SUCCEEDED / FAILED / CANCELLED` | 3 |
| `replay/ReplayRequest.java` (new) | Request body plus `validated()` (400 rules) | 3 |
| `replay/ReplayStatus.java` (new) | Response record, `NON_NULL` so an unused replay answers `{"state":"NONE"}` | 3 |
| `replay/ReplayTask.java` (new) | One task's mutable counters, cancel flag and snapshot | 3 |
| `replay/ReplayService.java` (new) | Single-task guard, batched `XRANGE` loop, cancel, retry limit | 3 |
| test `replay/ReplayRequestTest.java` (new) | Validation rules | 3 |
| test `replay/ReplayServiceTest.java` (new) | Counting, cancel, 409, failure after the retry limit, no dead letters, no ACK | 3 |
| `replay/ReplayController.java` (new) | The three endpoints | 4 |
| test `replay/ReplayApiIT.java` (new) | Validation through HTTP, 409 on cancel without a task, role matrix | 4 |
| test `replay/ReplayIT.java` (new) | End-to-end recovery, presence untouched, dead stream unchanged | 4 |

**conduit-ui**

| File | Responsibility | Task |
|---|---|---|
| `src/api/types.ts` (modify) | `ReplayState`, `ReplayStatus`, `ReplayRequest` | 5 |
| `src/api/replay.ts` (new) | The three calls | 5 |
| `src/views/TelemetryReplayView.vue` (new) | The page | 5 |
| `src/views/TelemetryReplayView.spec.ts` (new) | Spec §6.2 cases | 5 |
| `src/router/meta.ts` (modify) | `telemetry-replay` meta, verbatim from the spec | 5 |
| `src/router/index.ts` (modify) | The route | 5 |
| `src/layouts/menu.ts` (modify) | 「系统管理」gains「数据重放」for the platform admin | 5 |
| `src/layouts/menu.spec.ts` (modify) | Updated expectation | 5 |
| `src/test/viewTestUtils.ts` (modify) | Same route in the in-memory router | 5 |

**docs** (Task 6): `README.md`, `docs/durability-validation.md`, `CLAUDE.md` §1.2 and §4, `docs/test-reports/2026-10-20-telemetry-replay.md`.

**Task boundaries and why.** (1) The extraction, which is the only change to existing production code and therefore gets its own review gate; (2) the bus method, reviewable on its own because it is a contract change in a shared module; (3) the service, the only place with concurrency; (4) the HTTP surface and the two integration tests that prove the spec's acceptance criteria; (5) the page; (6) docs, full verification and acceptance. Tasks 2 and 3 could be merged, but the bus change lands in `conduit-common`, which `conduit-transport` and `conduit-e2e` also compile against, so it is worth its own gate.

---

## Task 1: Extract the shared ingest unit and the backoff out of `PipelineProcessor`

The spec (§4.3) requires「解码 → 校验 → 写库 → 写最新值」to become a reusable unit shared by normal consumption and replay, differing only in whether presence is updated and whether dead letters are written. This task does exactly that and nothing else: the behaviour of the running pipeline must not change, which the 21 existing tests in `PipelineProcessorTest` prove.

**Files:**
- Create: `conduit-server/src/main/java/dev/conduit/server/pipeline/ProcessingAbortedException.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/pipeline/Backoff.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/pipeline/TelemetryIngestor.java`
- Modify: `conduit-server/src/main/java/dev/conduit/server/pipeline/PipelineProcessor.java` (full replacement)
- Modify: `conduit-server/src/test/java/dev/conduit/server/pipeline/PipelineProcessorTest.java:225` (one line)
- Test: `conduit-server/src/test/java/dev/conduit/server/pipeline/BackoffTest.java` (new)
- Test: `conduit-server/src/test/java/dev/conduit/server/pipeline/TelemetryIngestorTest.java` (new)

**Interfaces:**
- Consumes: `DeviceIdentityLookup.find(String deviceKey) -> Optional<DeviceIdentity>`; `DeviceIdentity(deviceKey, tenantId, status, secretHash, secretSalt, productKey, tenantStatus)` with `tenantActive()`, `enabled()`, `productKey()`, `tenantId()`, `deviceKey()`; `ThingModelCache.find(String productKey) -> Optional<ProductProfile>`; `ProductProfile(String codec, Map<String,PropertySpec> properties)`; `DecoderRegistry.find(String codec) -> Optional<PayloadDecoder>`; `PayloadDecoder.decode(DecodeContext, byte[]) -> DecodedPayload(long ts, Map<String,Object> values)`; `FieldValidator.validate(Map<String,PropertySpec>, Map<String,Object>) -> FieldValidation(accepted, rejected)`; `TelemetryWriter.write(List<TelemetryPoint>) -> WriteResult`; `LatestValueStore.update(String, long, Map<String,Object>)`.
- Produces (Tasks 3 and 4 rely on these exact names and types):
  - `public class ProcessingAbortedException extends RuntimeException` with a `(String message)` constructor, package `dev.conduit.server.pipeline`.
  - `public class Backoff` with `Backoff(Duration initial, Duration max, int maxAttempts)`, `static Backoff unlimited(Duration initial, Duration max)`, `public <T> T run(String step, Supplier<T> action)`, `public static boolean isRecoverable(Throwable e)` and `public static final int UNLIMITED = 0`. `run` rethrows non-recoverable exceptions at once, rethrows the last recoverable exception once `maxAttempts` attempts have been made, and throws `ProcessingAbortedException` if the sleep is interrupted (restoring the interrupt flag).
  - `@Component public class TelemetryIngestor` with the constructor `TelemetryIngestor(DeviceIdentityLookup identities, ThingModelCache thingModels, DecoderRegistry decoders, TelemetryWriter writer, LatestValueStore latest, MeterRegistry meters)`, the methods `public Prepared prepare(String traceId, RawMessage message)`, `public WriteResult write(List<TelemetryPoint> points)`, `public void updateLatest(TelemetryPoint point)`, and the nested `public record Prepared(TelemetryPoint point, List<RejectedField> rejected)`.
  - `PipelineProcessor` keeps its package-private test constructor signature unchanged, so `PipelineProcessorTest` keeps compiling.

**Decisions recorded here:**
- `ProcessingAbortedException` is promoted to a top-level class because `Backoff` throws it and must not depend on `PipelineProcessor`. The only reference outside the production class is one test assertion, which Step 9 updates.
- `TelemetryIngestor.prepare` keeps throwing `DecodeException`. Replay reads `e.reason()` to tell「全部测点被拒」(`FIELD_REJECTED`, counted as `rejected`) from every other reason (counted as `skipped`), and never records a dead letter.
- `prepare` takes a `traceId` (the delivery id in the pipeline, the stream entry id in replay) only for log correlation, so the ingestor never has to know about `Delivery`.
- The `conduit.pipeline.rejected_fields` counter moves with the validation into the ingestor, so replay counts rejected fields the same way. `conduit.pipeline.processed` and `conduit.pipeline.dead` stay in `PipelineProcessor`, because they describe consumption, not ingest.
- `PipelineProcessor` keeps an unlimited `Backoff`: for a real delivery, staying unacked is always better than dropping data.

- [ ] **Step 1: Write the failing backoff test**

Create `conduit-server/src/test/java/dev/conduit/server/pipeline/BackoffTest.java`:

```java
package dev.conduit.server.pipeline;

import dev.conduit.server.telemetry.TelemetryUnavailableException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backoff 单元测试：可恢复错误原地重试、不可恢复错误立即抛出、有上限时抛出最后一次异常、被中断时中止并保留中断标志。 */
class BackoffTest {

    private static final Duration FAST = Duration.ofMillis(1);

    private static Backoff capped(int maxAttempts) {
        return new Backoff(FAST, Duration.ofMillis(5), maxAttempts);
    }

    private static TelemetryUnavailableException down() {
        return new TelemetryUnavailableException(new RuntimeException("down"));
    }

    @Test
    void retriesRecoverableFailuresUntilTheActionSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Backoff backoff = Backoff.unlimited(FAST, Duration.ofMillis(5));

        String result = backoff.run("influx write", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw down();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void rethrowsNonRecoverableFailuresWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> capped(5).run("lookup", () -> {
            attempts.incrementAndGet();
            throw new BadSqlGrammarException("q", "select", new SQLException("x"));
        })).isInstanceOf(BadSqlGrammarException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void rethrowsTheLastFailureOnceTheAttemptLimitIsReached() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> capped(3).run("influx write", () -> {
            attempts.incrementAndGet();
            throw down();
        })).isInstanceOf(TelemetryUnavailableException.class);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void treatsADatabaseOutageWrappedByMyBatisAsRecoverable() {
        assertThat(Backoff.isRecoverable(new MyBatisSystemException(
                new PersistenceException(new CannotGetJdbcConnectionException("down"))))).isTrue();
        assertThat(Backoff.isRecoverable(new BadSqlGrammarException("q", "select", new SQLException("x")))).isFalse();
    }

    @Test
    void anInterruptedWaitAbortsAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> capped(5).run("influx write", () -> {
                throw down();
            })).isInstanceOf(ProcessingAbortedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=BackoffTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class Backoff`.

- [ ] **Step 3: Create the exception and the backoff**

Create `conduit-server/src/main/java/dev/conduit/server/pipeline/ProcessingAbortedException.java`:

```java
package dev.conduit.server.pipeline;

/**
 * 处理被中断（worker 停止，或重放任务所在线程被中断）：中止当前这批，已读取的投递保持未 ACK，
 * 之后由认领机制重新投递；重放时由调用方把任务置为 CANCELLED。
 */
public class ProcessingAbortedException extends RuntimeException {

    public ProcessingAbortedException(String message) {
        super(message);
    }
}
```

Create `conduit-server/src/main/java/dev/conduit/server/pipeline/Backoff.java`:

```java
package dev.conduit.server.pipeline;

import dev.conduit.server.telemetry.TelemetryUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 下游暂时不可用时的原地指数退避重试：只有可恢复错误才重试，延迟从 initial 起翻倍、封顶 max。
 * 流水线用无上限版本（消息保持未 ACK 比丢数据好）；重放用有上限版本，超限后把异常抛给调用方，由它把任务置为 FAILED。
 */
public class Backoff {

    /** maxAttempts 取该值表示不限次数。 */
    public static final int UNLIMITED = 0;

    private static final Logger log = LoggerFactory.getLogger(Backoff.class);
    private static final int MAX_CAUSE_DEPTH = 16;

    private final Duration initial;
    private final Duration max;
    private final int maxAttempts;

    /**
     * @param initial     首次重试前的等待时间
     * @param max         等待时间上限
     * @param maxAttempts 最多尝试次数（含第一次），{@link #UNLIMITED} 表示不限
     */
    public Backoff(Duration initial, Duration max, int maxAttempts) {
        this.initial = initial;
        this.max = max;
        this.maxAttempts = maxAttempts;
    }

    /** 不限次数的退避，流水线消费用：只要消息没处理完就一直等下游恢复。 */
    public static Backoff unlimited(Duration initial, Duration max) {
        return new Backoff(initial, max, UNLIMITED);
    }

    /**
     * 原地执行 action，仅可恢复错误（见 {@link #isRecoverable}）才退避重试。
     *
     * @param step   步骤名，仅用于日志
     * @param action 要执行的动作；无返回值时返回 null
     * @return action 的返回值
     * @throws ProcessingAbortedException 等待期间线程被中断（会恢复中断标志）
     * @throws RuntimeException           不可恢复的异常原样抛出；达到 maxAttempts 后抛出最后一次可恢复异常
     */
    public <T> T run(String step, Supplier<T> action) {
        long delay = initial.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.debug("{} recovered after {} attempts", step, attempt);
                }
                return result;
            } catch (RuntimeException e) {
                if (!isRecoverable(e)) {
                    throw e;
                }
                if (maxAttempts != UNLIMITED && attempt >= maxAttempts) {
                    log.warn("{} still unavailable after {} attempts, giving up: {}", step, attempt, e.getMessage());
                    throw e;
                }
                log.warn("{} unavailable (attempt {}), retrying in {} ms: {}", step, attempt, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ProcessingAbortedException("Interrupted while retrying " + step);
                }
                delay = Math.min(delay * 2, max.toMillis());
            }
        }
    }

    /**
     * 仅暂时性故障值得原地重试；其余（语法错误、完整性冲突、WRONGTYPE 等）重试也不会成功。
     * 沿 cause 链查找：MyBatis 会把取连接失败（CannotGetJdbcConnectionException）包装成 MyBatisSystemException，
     * 只看顶层会把数据库宕机误判为不可恢复。
     */
    public static boolean isRecoverable(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TelemetryUnavailableException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run the backoff test and confirm it passes**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=BackoffTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing ingestor test**

Create `conduit-server/src/test/java/dev/conduit/server/pipeline/TelemetryIngestorTest.java`:

```java
package dev.conduit.server.pipeline;

import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.codec.DecodedPayload;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.PayloadDecoder;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TelemetryIngestor 单元测试：身份与物模型检查、解码校验后的数据点、全部测点被拒的 FIELD_REJECTED，以及写库与最新值的委托。 */
class TelemetryIngestorTest {

    DeviceIdentityLookup identities = mock(DeviceIdentityLookup.class);
    ThingModelCache thingModels = mock(ThingModelCache.class);
    DecoderRegistry decoders = mock(DecoderRegistry.class);
    PayloadDecoder decoder = mock(PayloadDecoder.class);
    TelemetryWriter writer = mock(TelemetryWriter.class);
    LatestValueStore latest = mock(LatestValueStore.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    TelemetryIngestor ingestor;

    @BeforeEach
    void setUp() {
        ingestor = new TelemetryIngestor(identities, thingModels, decoders, writer, latest, meters);
        when(identities.find("d1")).thenReturn(Optional.of(
                new DeviceIdentity("d1", 7L, "ENABLED", "h", "s", "pk", "ACTIVE")));
        when(thingModels.find("pk")).thenReturn(Optional.of(new ProductProfile("standard-json", Map.of())));
        when(decoders.find("standard-json")).thenReturn(Optional.of(decoder));
        when(decoder.decode(any(), any())).thenReturn(new DecodedPayload(1760400000000L, Map.of("Ua", 220.5)));
    }

    private static RawMessage message(String deviceKey) {
        return new RawMessage("pk", deviceKey, MessageType.PROPERTY,
                "{}".getBytes(StandardCharsets.UTF_8), 1760400000001L, Map.of());
    }

    private void useModel(PropertySpec spec) {
        when(thingModels.find("pk")).thenReturn(Optional.of(
                new ProductProfile("standard-json", Map.of(spec.identifier(), spec))));
    }

    @Test
    void buildsATelemetryPointFromTheDecodedAndValidatedPayload() {
        TelemetryIngestor.Prepared prepared = ingestor.prepare("1-0", message("d1"));

        assertThat(prepared.point()).isEqualTo(
                new TelemetryPoint(7L, "pk", "d1", 1760400000000L, Map.of("Ua", 220.5)));
        assertThat(prepared.rejected()).isEmpty();
    }

    @Test
    void anUnknownDeviceIsReportedAsUnknownDevice() {
        when(identities.find("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestor.prepare("1-0", message("ghost")))
                .isInstanceOf(DecodeException.class)
                .extracting(e -> ((DecodeException) e).reason())
                .isEqualTo(DeadReason.UNKNOWN_DEVICE);
    }

    @Test
    void aMessageWhoseFieldsAreAllRejectedIsReportedAsFieldRejected() {
        useModel(new PropertySpec("Ua", "Ua", DataType.DOUBLE, null, null, null, null, Map.of()));
        when(decoder.decode(any(), any())).thenReturn(new DecodedPayload(1760400000000L, Map.of("Ua", "abc")));

        assertThatThrownBy(() -> ingestor.prepare("1-0", message("d1")))
                .isInstanceOf(DecodeException.class)
                .extracting(e -> ((DecodeException) e).reason())
                .isEqualTo(DeadReason.FIELD_REJECTED);
    }

    @Test
    void writeAndLatestUpdateAreDelegated() {
        TelemetryPoint point = new TelemetryPoint(7L, "pk", "d1", 1760400000000L, Map.of("Ua", 220.5));
        when(writer.write(anyList())).thenReturn(WriteResult.ok());

        assertThat(ingestor.write(List.of(point)).rejected()).isEmpty();
        ingestor.updateLatest(point);

        verify(writer).write(List.of(point));
        verify(latest).update("d1", 1760400000000L, Map.of("Ua", 220.5));
    }
}
```

- [ ] **Step 6: Run it and confirm it fails**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=TelemetryIngestorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class TelemetryIngestor`.

- [ ] **Step 7: Create `TelemetryIngestor`**

Create `conduit-server/src/main/java/dev/conduit/server/pipeline/TelemetryIngestor.java`:

```java
package dev.conduit.server.pipeline;

import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.codec.DecodeContext;
import dev.conduit.server.pipeline.codec.DecodedPayload;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.PayloadDecoder;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 正常消费与遥测重放共用的处理单元（V0.2-R3 spec 4.3）：解码 → 物模型校验 → 写 InfluxDB → 写最新值。
 * 这里不写死信、不 ACK、不更新在线状态——这三件事由调用方决定：流水线三者都做，重放一个都不做。
 * 下游不可用时原样抛出异常，由调用方用 {@link Backoff} 决定重试策略。
 */
@Component
public class TelemetryIngestor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestor.class);

    /**
     * 一条已解码并校验、等待写库的属性消息。
     *
     * @param point    待写入的数据点，fields 只含通过校验的测点
     * @param rejected 被剔除的测点；流水线据此补写 FIELD_REJECTED 记录，重放只是忽略
     */
    public record Prepared(TelemetryPoint point, List<RejectedField> rejected) {
    }

    private final DeviceIdentityLookup identities;
    private final ThingModelCache thingModels;
    private final DecoderRegistry decoders;
    private final TelemetryWriter writer;
    private final LatestValueStore latest;
    private final MeterRegistry meters;

    public TelemetryIngestor(DeviceIdentityLookup identities, ThingModelCache thingModels, DecoderRegistry decoders,
                             TelemetryWriter writer, LatestValueStore latest, MeterRegistry meters) {
        this.identities = identities;
        this.thingModels = thingModels;
        this.decoders = decoders;
        this.writer = writer;
        this.latest = latest;
        this.meters = meters;
    }

    /**
     * 「解码 → 校验」阶段：依次检查设备存在、所属租户已启用、设备已启用、productKey 一致，
     * 取产品物模型与 codec 并解码，再逐测点校验。被剔除的测点计入 conduit.pipeline.rejected_fields 指标。
     *
     * @param traceId 仅用于日志关联：流水线传投递 id，重放传流条目 id
     * @throws DecodeException UNKNOWN_DEVICE / TENANT_DISABLED / DEVICE_DISABLED / PRODUCT_MISMATCH / UNKNOWN_CODEC、
     *                         解码失败，或全部测点被拒绝（FIELD_REJECTED，details 含被拒详情）
     */
    public Prepared prepare(String traceId, RawMessage message) {
        DeviceIdentity identity = identities.find(message.deviceKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE, message.deviceKey()));
        if (!identity.tenantActive()) {
            throw new DecodeException(DeadReason.TENANT_DISABLED, message.deviceKey());
        }
        if (!identity.enabled()) {
            throw new DecodeException(DeadReason.DEVICE_DISABLED, message.deviceKey());
        }
        if (!identity.productKey().equals(message.productKey())) {
            throw new DecodeException(DeadReason.PRODUCT_MISMATCH,
                    message.productKey() + " != " + identity.productKey());
        }
        ProductProfile profile = thingModels.find(identity.productKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE,
                        "product not found: " + identity.productKey()));
        PayloadDecoder decoder = decoders.find(profile.codec())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_CODEC, String.valueOf(profile.codec())));
        DecodedPayload decoded = decoder.decode(
                new DecodeContext(identity.productKey(), identity.deviceKey(), message.receivedAt()),
                message.payload());
        log.debug("Decoded {}: productKey={} deviceKey={} codec={} fields={} ts={}", traceId,
                identity.productKey(), identity.deviceKey(), profile.codec(), decoded.values().size(), decoded.ts());
        FieldValidation validation = FieldValidator.validate(profile.properties(), decoded.values());
        logValidation(traceId, identity.deviceKey(), validation);
        validation.rejected().forEach(r ->
                meters.counter("conduit.pipeline.rejected_fields", "reason", r.reason().name()).increment());
        if (validation.accepted().isEmpty()) {
            throw new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected",
                    RejectedFields.details(validation.rejected()));
        }
        TelemetryPoint point = new TelemetryPoint(identity.tenantId(), identity.productKey(), identity.deviceKey(),
                decoded.ts(), validation.accepted());
        return new Prepared(point, validation.rejected());
    }

    /**
     * 批量写入时序库。
     *
     * @return 写入结果；其中 rejected 的 index 指向入参列表中被 InfluxDB 拒绝的数据点（通常是字段类型冲突）
     * @throws dev.conduit.server.telemetry.TelemetryUnavailableException 下游不可用（可重试）
     */
    public WriteResult write(List<TelemetryPoint> points) {
        return writer.write(points);
    }

    /** 更新该设备的最新值；Lua 脚本逐测点比较时间戳，重放的旧值不会覆盖更新的值。 */
    public void updateLatest(TelemetryPoint point) {
        latest.update(point.deviceKey(), point.ts(), point.fields());
    }

    /**
     * 记录校验结果：DEBUG 一行（通过/剔除数量，有剔除时附字段名与原因，不含值）；TRACE 逐测点列出通过的值（截断 256 字符）。
     */
    private void logValidation(String traceId, String deviceKey, FieldValidation validation) {
        if (log.isDebugEnabled()) {
            String rejectedText = validation.rejected().isEmpty() ? "" : " rejected=" + validation.rejected().stream()
                    .map(r -> r.field() + ":" + r.reason()).collect(Collectors.joining(",", "[", "]"));
            log.debug("Validated {}: deviceKey={} accepted={} rejected={}{}", traceId, deviceKey,
                    validation.accepted().size(), validation.rejected().size(), rejectedText);
        }
        if (log.isTraceEnabled()) {
            Map<String, Object> accepted = validation.accepted();
            accepted.forEach((field, value) -> log.trace("Accepted field {}.{} = {}", deviceKey, field,
                    truncate(String.valueOf(value))));
        }
    }

    /** 把值截断到 256 个字符供 TRACE 日志使用。 */
    private static String truncate(String value) {
        return value.length() <= 256 ? value : value.substring(0, 256) + "...(truncated)";
    }
}
```

- [ ] **Step 8: Run the ingestor test and confirm it passes**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=TelemetryIngestorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 9: Point the existing pipeline test at the promoted exception**

In `conduit-server/src/test/java/dev/conduit/server/pipeline/PipelineProcessorTest.java`, replace the single line

```java
                    .isInstanceOf(PipelineProcessor.ProcessingAbortedException.class);
```

with

```java
                    .isInstanceOf(ProcessingAbortedException.class);
```

Change nothing else in that file: every other test there is the regression net for this refactor.

- [ ] **Step 10: Rewrite `PipelineProcessor` on top of the two new pieces**

Replace the whole content of `conduit-server/src/main/java/dev/conduit/server/pipeline/PipelineProcessor.java` with:

```java
package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.TelemetryIngestor.Prepared;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 处理一批投递：属性消息交给 {@link TelemetryIngestor}（解码 → 校验 → 写 InfluxDB → 写最新值），本类只负责消费侧的事——
 * 死信、ACK 与在线状态。被剔除的测点另写一条 FIELD_REJECTED 死信记录。不可恢复的错误进死信（并 ACK）；
 * 下游不可用时由 {@link Backoff} 原地无上限退避重试，未处理的投递保持未 ACK，之后由认领机制重新投递。
 * 属性数据落库并 ACK 后，按数据来源批量把设备标记为在线（尽力而为，失败不影响 ACK；规则见 PresenceStore）。
 * 重复投递由 InfluxDB 同 ts 覆盖与最新值按 ts 比较吸收。
 */
@Component
public class PipelineProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

    /** 已通过解码与校验、等待批量写库的属性消息：原投递与处理结果。 */
    private record PendingProperty(Delivery delivery, Prepared prepared) {
    }

    private final MessageBus bus;
    private final PipelineProperties props;
    private final TelemetryIngestor ingestor;
    private final PresenceHandler presenceHandler;
    private final PresenceStore presence;
    private final MeterRegistry meters;
    private final Backoff backoff;

    @Autowired
    public PipelineProcessor(MessageBus bus, PipelineProperties props, TelemetryIngestor ingestor,
                             PresenceHandler presenceHandler, PresenceStore presence, MeterRegistry meters) {
        this(bus, props, ingestor, presenceHandler, presence, meters,
                Backoff.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    /** 可指定退避策略的构造器，测试用。 */
    PipelineProcessor(MessageBus bus, PipelineProperties props, TelemetryIngestor ingestor,
                      PresenceHandler presenceHandler, PresenceStore presence, MeterRegistry meters,
                      Backoff backoff) {
        this.bus = bus;
        this.props = props;
        this.ingestor = ingestor;
        this.presenceHandler = presenceHandler;
        this.presence = presence;
        this.meters = meters;
        this.backoff = backoff;
    }

    /**
     * 测试构造器：沿用提取 TelemetryIngestor 之前的协作者列表，内部自行组装 ingestor 与快速退避，
     * 这样既能单独 mock 各协作者，又不必在测试里重复装配。
     */
    PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                      ThingModelCache thingModels, DecoderRegistry decoders, PresenceHandler presenceHandler,
                      TelemetryWriter writer, LatestValueStore latest, PresenceStore presence,
                      MeterRegistry meters, Duration initialBackoff, Duration maxBackoff) {
        this(bus, props, new TelemetryIngestor(identities, thingModels, decoders, writer, latest, meters),
                presenceHandler, presence, meters, Backoff.unlimited(initialBackoff, maxBackoff));
    }

    /**
     * 处理一批投递：逐条分流，在线/离线消息处理后统一 ACK，属性消息攒成一批后统一写库。
     * 每条投递的去向：投递次数超过 maxDeliveries 进 MAX_DELIVERY_EXCEEDED；信封损坏进 MALFORMED_ENVELOPE；
     * EVENT 暂不支持，进 UNSUPPORTED_TYPE；DecodeException 按其原因进死信；其他运行时异常只让该条进 PROCESSING_ERROR，
     * 不影响同批其他消息。
     *
     * @throws ProcessingAbortedException 线程在退避重试中被中断；已读取但未处理的投递保持未 ACK，之后重新认领
     */
    public void process(List<Delivery> batch) {
        log.debug("Processing batch of {} deliveries", batch.size());
        List<String> presenceAcks = new ArrayList<>();
        List<PendingProperty> properties = new ArrayList<>();

        for (Delivery delivery : batch) {
            if (delivery.deliveryCount() > props.maxDeliveries()) {
                dead(delivery, DeadReason.MAX_DELIVERY_EXCEEDED);
                continue;
            }
            RawMessage message;
            try {
                message = delivery.message();
            } catch (IllegalArgumentException e) {
                dead(delivery, DeadReason.MALFORMED_ENVELOPE);
                continue;
            }
            try {
                switch (message.type()) {
                    case PROPERTY -> properties.add(new PendingProperty(delivery,
                            backoff.run("lookup", () -> ingestor.prepare(delivery.id(), message))));
                    case PRESENCE_CONNECTED, PRESENCE_DISCONNECTED -> {
                        log.debug("Presence message {}: deviceKey={} type={}", delivery.id(), message.deviceKey(),
                                message.type());
                        backoff.run("presence", () -> {
                            presenceHandler.handle(message);
                            return null;
                        });
                        presenceAcks.add(delivery.id());
                        meters.counter("conduit.pipeline.processed", "type", "presence").increment();
                    }
                    case EVENT -> dead(delivery, DeadReason.UNSUPPORTED_TYPE);
                }
            } catch (DecodeException e) {
                log.debug("Dead-lettering {}: {} {}", delivery.id(), e.reason(), e.getMessage());
                dead(delivery, e.reason(), e.details());
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure processing delivery {}", delivery.id(), e);
                dead(delivery, DeadReason.PROCESSING_ERROR);
            }
        }

        ack(presenceAcks);
        if (!properties.isEmpty()) {
            writeProperties(properties);
        }
    }

    /**
     * 批量写 InfluxDB，再逐条更新最新值、补写 FIELD_REJECTED 记录并 ACK。
     * InfluxDB 拒绝的数据点（字段类型冲突）对应的投递进 FIELD_TYPE_CONFLICT；
     * 单条收尾阶段的意外错误只让该条进 PROCESSING_ERROR。其余投递在写库成功后才一次性 ACK（至少一次投递）。
     */
    private void writeProperties(List<PendingProperty> items) {
        List<TelemetryPoint> points = items.stream().map(i -> i.prepared().point()).toList();
        long writeStart = System.nanoTime();
        WriteResult result = backoff.run("influx write", () -> ingestor.write(points));
        log.debug("Influx write: lines={} rejected={} elapsedMs={}", points.size(), result.rejected().size(),
                (System.nanoTime() - writeStart) / 1_000_000);
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());

        List<String> acks = new ArrayList<>();
        Map<String, Long> reportedAt = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            PendingProperty item = items.get(i);
            if (rejected.contains(i)) {
                dead(item.delivery(), DeadReason.FIELD_TYPE_CONFLICT);
                continue;
            }
            TelemetryPoint p = item.prepared().point();
            try {
                backoff.run("latest update", () -> {
                    ingestor.updateLatest(p);
                    return null;
                });
                log.debug("Latest values updated: deviceKey={} fields={} ts={}", p.deviceKey(), p.fields().size(),
                        p.ts());
                if (!item.prepared().rejected().isEmpty()) {
                    Map<String, String> details = RejectedFields.details(item.prepared().rejected());
                    // 先留痕再 ACK：两步之间崩溃只会产生重复死信记录，不会丢失
                    backoff.run("dead record", () -> {
                        bus.recordDead(item.delivery(), DeadReason.FIELD_REJECTED.name(), details);
                        return null;
                    });
                }
                acks.add(item.delivery().id());
                reportedAt.merge(p.deviceKey(), item.delivery().message().receivedAt(), Math::max);
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure finishing delivery {}", item.delivery().id(), e);
                dead(item.delivery(), DeadReason.PROCESSING_ERROR);
            }
        }
        ack(acks);
        markOnline(reportedAt);
        meters.counter("conduit.pipeline.processed", "type", "property").increment(acks.size());
    }

    /**
     * 把本批成功落库并已 ACK 的设备按「数据」来源标记为在线（{@link PresenceStore#applyDataBatch}，一次 pipeline 往返）：
     * 补偿「设备在 transport 订阅之前就已连接、没有收到连接事件」的情况。时间取该设备本批消息的最大 receivedAt
     * （transport 主机时钟，与 EMQX 事件时钟不同源），因此数据<b>不会推进</b>乱序保护用的 since：已在线不变，
     * 离线设备只有数据时间超过断连时间加宽限期才会复活，晚到的断连事件仍然生效。
     * 必须在 ACK 之后执行；这是尽力而为的附带动作，Redis 失败只在每批记一次 WARN，不重试、不影响 ACK。
     * 遥测重放不走这条路径：重放的是旧数据，会把已离线设备误标为在线（spec 4.3）。
     */
    private void markOnline(Map<String, Long> reportedAt) {
        if (reportedAt.isEmpty()) {
            return;
        }
        try {
            presence.applyDataBatch(reportedAt);
        } catch (RuntimeException e) {
            log.warn("Failed to mark {} device(s) online from data (best effort, ignored): {}", reportedAt.size(),
                    e.getMessage());
        }
    }

    /** 批量 ACK（空列表直接返回），暂时性故障原地重试。 */
    private void ack(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        backoff.run("ack", () -> {
            bus.ack(props.group(), ids);
            return null;
        });
        log.debug("Acked {} deliveries", ids.size());
    }

    /**
     * 无详情的死信。DEBUG 记一行；PROCESSING_ERROR 的调用方已记 ERROR（含堆栈），这里不再重复。
     * 带详情的重载由调用方（DecodeException 分支）已记 DEBUG。
     */
    private void dead(Delivery delivery, DeadReason reason) {
        if (reason != DeadReason.PROCESSING_ERROR) {
            log.debug("Dead-lettering {}: {}", delivery.id(), reason);
        }
        dead(delivery, reason, Map.of());
    }

    /** 写死信记录、ACK 原投递并计数 conduit.pipeline.dead；details 写入死信记录。 */
    private void dead(Delivery delivery, DeadReason reason, Map<String, String> details) {
        // 先留痕再 ACK，分两步各自重试：ACK 暂时失败时不会重复写死信记录
        backoff.run("dead letter", () -> {
            bus.recordDead(delivery, reason.name(), details);
            return null;
        });
        ack(List.of(delivery.id()));
        meters.counter("conduit.pipeline.dead", "reason", reason.name()).increment();
    }
}
```

- [ ] **Step 11: Run the three unit test classes together and confirm they pass**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=BackoffTest,TelemetryIngestorTest,PipelineProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass; `PipelineProcessorTest` runs 21 tests with 0 failures. If one of its tests fails, the refactor changed behaviour — fix the production code, never the assertions.

- [ ] **Step 12: Run the pipeline integration test to confirm the wiring still works**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test=PipelineIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: `PipelineIT` passes. This proves Spring can build `TelemetryIngestor` and inject it into the new `PipelineProcessor` constructor.

- [ ] **Step 13: Commit**

```bash
git add conduit-server/src/main/java/dev/conduit/server/pipeline conduit-server/src/test/java/dev/conduit/server/pipeline
git commit -m "refactor(pipeline): extract the shared telemetry ingest unit and backoff"
```

---

## Task 2: Range read on the message bus

Replay reads the up-stream by entry id (`XRANGE`), which the bus does not expose yet. A Redis stream entry id starts with its millisecond timestamp, so a time range is an id range and no extra index is needed (spec §4.3 item 1).

**Files:**
- Modify: `conduit-common/src/main/java/dev/conduit/common/bus/MessageBus.java`
- Modify: `conduit-common/src/main/java/dev/conduit/common/bus/RedisStreamMessageBus.java`
- Test: `conduit-common/src/test/java/dev/conduit/common/bus/RedisStreamMessageBusIT.java` (add tests)

**Interfaces:**
- Consumes: `MessageBusProperties.streamKey()`; `Delivery(String id, Map<String,String> fields, long deliveryCount)`; Spring Data Redis `StringRedisTemplate.opsForStream().range(K key, Range<String> range, Limit limit)`.
- Produces (Task 3 relies on this exact signature):
  - `List<Delivery> MessageBus.range(String startId, String endId, int count)` — reads the up-stream with `XRANGE`, **both ends inclusive**, at most `count` entries, in ascending id order. The returned `Delivery` objects have `deliveryCount == 0` because they did not come through a consumer group; the caller must never `ack` them.

**Decisions recorded here:**
- Both ends are inclusive and the ids are plain strings. The caller, not the bus, turns a half-open millisecond range into ids: start `"<from>-0"`, end `"<to-1>-18446744073709551615"` (the largest sequence number in the last included millisecond). That keeps the bus free of time semantics and avoids depending on Redis exclusive range syntax (`(`), whose support differs across client versions.
- Paging is also the caller's job: it continues from the id after the last one it saw. The bus method stays a single `XRANGE`.
- There is no fake `MessageBus` anywhere in the repository — `RedisStreamMessageBus` is the only implementation and every other test uses a Mockito mock — so adding an interface method breaks no test double. Mockito returns `null` for unstubbed methods, so any new test that needs `range` must stub it.

- [ ] **Step 1: Write the failing range tests**

In `conduit-common/src/test/java/dev/conduit/common/bus/RedisStreamMessageBusIT.java`, add these four tests inside the class (keep everything that is already there):

```java
    @Test
    void rangeReturnsTheEntriesBetweenTwoIdsInclusive() {
        String first = bus.publish(message("d1"));
        String second = bus.publish(message("d2"));
        bus.publish(message("d3"));

        List<Delivery> entries = bus.range(first, second, 100);

        assertThat(entries).extracting(Delivery::id).containsExactly(first, second);
        assertThat(entries.getFirst().message().deviceKey()).isEqualTo("d1");
        assertThat(entries.getFirst().fields()).containsEntry("dk", "d1");
    }

    @Test
    void rangeMarksEntriesAsNotDeliveredThroughAGroup() {
        String id = bus.publish(message("d1"));

        assertThat(bus.range(id, id, 100)).singleElement()
                .extracting(Delivery::deliveryCount).isEqualTo(0L);
    }

    @Test
    void rangeHonoursTheCountLimit() {
        bus.publish(message("d1"));
        bus.publish(message("d2"));
        bus.publish(message("d3"));

        assertThat(bus.range("0-0", "99999999999999-0", 2)).hasSize(2);
    }

    @Test
    void rangeDoesNotConsumeTheEntries() {
        bus.publish(message("d1"));
        bus.publish(message("d2"));

        assertThat(bus.range("0-0", "99999999999999-0", 100)).hasSize(2);
        assertThat(bus.read(GROUP, "c1", 10, Duration.ofMillis(100))).hasSize(2);
    }
```

The file already imports `java.time.Duration`, `java.util.List` and `org.assertj.core.api.Assertions.assertThat`, and already has the `message(String deviceKey)` helper and the `GROUP` constant, so no new imports are needed.

- [ ] **Step 2: Run them and confirm they fail**

Run: `mvn -B -pl conduit-common verify -Dtest=NONE -Dit.test=RedisStreamMessageBusIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: method range(java.lang.String,java.lang.String,int)`.

- [ ] **Step 3: Add the method to the bus contract**

In `conduit-common/src/main/java/dev/conduit/common/bus/MessageBus.java`, add this method after `recordDead` (the last method of the interface):

```java
    /**
     * 按条目 id 区间读取上行流的原始条目（XRANGE，两端均含），最多 count 条，按 id 升序返回。
     * 用于遥测重放：Redis 流条目 id 的前半段就是毫秒时间戳，所以时间范围直接对应 id 范围。
     * 不经过消费组，不影响待确认列表：返回的 Delivery 的 deliveryCount 固定为 0，调用方不得 ack。
     *
     * @param startId 起始条目 id（含），如 {@code "1760400000000-0"}
     * @param endId   结束条目 id（含）
     * @param count   本次最多返回的条数
     */
    List<Delivery> range(String startId, String endId, int count);
```

Also extend the contract list in the interface Javadoc: add this `<li>` after the `recordDead` one:

```java
 *   <li>{@link #range} 只读、不进消费组、不改变待确认列表，返回的 deliveryCount 为 0。</li>
```

- [ ] **Step 4: Implement it on Redis Streams**

In `conduit-common/src/main/java/dev/conduit/common/bus/RedisStreamMessageBus.java`, add these two imports to the existing import block:

```java
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
```

and add this method right after `recordDead`:

```java
    /** XRANGE 上行流 [startId, endId]，最多 count 条；不经过消费组，deliveryCount 固定为 0。 */
    @Override
    public List<Delivery> range(String startId, String endId, int count) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(props.streamKey(), Range.closed(startId, endId), Limit.limit().count(count));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(r -> new Delivery(r.getId().getValue(), toStringMap(r.getValue()), 0))
                .toList();
    }
```

- [ ] **Step 5: Run the bus integration test and confirm it passes**

Run: `mvn -B -pl conduit-common verify -Dtest=NONE -Dit.test=RedisStreamMessageBusIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: all tests of the class pass, including the four new ones.

- [ ] **Step 6: Confirm the other modules still compile against the new contract**

Run: `mvn -B -q -DskipTests install`
Expected: `BUILD SUCCESS`. This catches any other `MessageBus` implementation that would now be abstract. (There should be none; if the build reports one, stop and report it.)

- [ ] **Step 7: Commit**

```bash
git add conduit-common/src/main/java/dev/conduit/common/bus conduit-common/src/test/java/dev/conduit/common/bus
git commit -m "feat(bus): read stream entries by id range"
```

---

## Task 3: Replay request, task state, cancel and the batched execution loop

This is the only concurrent part of the feature: one task at a time in an `AtomicReference`, running on a virtual thread, with counters read by request threads while the worker writes them.

**Files:**
- Create: `conduit-server/src/main/java/dev/conduit/server/replay/ReplayState.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/replay/ReplayRequest.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/replay/ReplayStatus.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/replay/ReplayTask.java`
- Create: `conduit-server/src/main/java/dev/conduit/server/replay/ReplayService.java`
- Test: `conduit-server/src/test/java/dev/conduit/server/replay/ReplayRequestTest.java` (new)
- Test: `conduit-server/src/test/java/dev/conduit/server/replay/ReplayServiceTest.java` (new)

**Interfaces:**
- Consumes: `MessageBus.range(String startId, String endId, int count)` (Task 2); `TelemetryIngestor.prepare(String traceId, RawMessage message) -> Prepared`, `TelemetryIngestor.write(List<TelemetryPoint>) -> WriteResult`, `TelemetryIngestor.updateLatest(TelemetryPoint)` (Task 1); `Backoff`, `ProcessingAbortedException` (Task 1); `ApiException.badRequest/conflict`; `Clock` bean; `Delivery.message()`; `MessageType.PROPERTY`; `DeadReason.FIELD_REJECTED`; `WriteResult.Rejected.index()`.
- Produces (Task 4 and Task 5 rely on these exact names):
  - `public enum ReplayState { NONE, RUNNING, SUCCEEDED, FAILED, CANCELLED }`
  - `public record ReplayRequest(Long from, Long to)` with `public ReplayRequest validated()`, `public static final long MAX_MILLIS = 253402300799999L`, `public static final long MAX_SPAN_MILLIS = 21600000L`.
  - `public record ReplayStatus(ReplayState state, Long from, Long to, Long startedAt, Long finishedAt, Long scanned, Long written, Long skipped, Long rejected, String error)` annotated `@JsonInclude(JsonInclude.Include.NON_NULL)`, with `public static ReplayStatus none()`.
  - `@Service public class ReplayService` with `public ReplayStatus start(ReplayRequest request)`, `public ReplayStatus status()`, `public ReplayStatus cancel()`, and the package-private seam `ReplayService(MessageBus bus, TelemetryIngestor ingestor, Clock clock, Backoff backoff, Executor executor)` plus the package-private `void run(ReplayTask task)`.
  - Constants `ReplayService.BATCH_SIZE = 500` and `ReplayService.MAX_ATTEMPTS = 5`.

**Decisions recorded here:**
- `ReplayStatus` uses boxed `Long` counters and `@JsonInclude(NON_NULL)` so that a replay that has never run answers exactly `{"state":"NONE"}`, as the spec writes it. A running or finished task fills every field.
- The spec says「连续失败超过重试上限」without naming the limit. This plan sets `MAX_ATTEMPTS = 5` with the same 1s→30s delays as the pipeline, i.e. a write keeps retrying for about 15 s before the task fails. It is a constant with a Chinese comment, not configuration: replay is a manual recovery action that the operator can simply run again.
- The execution thread is supplied through an `Executor` field. Production passes a virtual-thread executor; `ReplayServiceTest` passes either a direct executor (`Runnable::run`, so `start` runs the whole replay synchronously) or a "pending" executor that stores the `Runnable` without running it, which makes the 409 and cancel paths deterministic without sleeping.
- Replay never touches `PresenceStore` or `bus.recordDead` / `bus.ack` — not by a flag but because it does not hold those collaborators at all. `ReplayServiceTest` still asserts it with Mockito `never()`, so a future change that adds them fails the test.
- Partially rejected messages (some fields dropped by the thing model, some accepted) are written with their accepted fields and counted as `written`. Only a message whose fields are **all** rejected counts as `rejected`. That mirrors the pipeline, minus the dead-letter record.
- The next batch starts at the entry after the last one returned, computed with `Long.parseUnsignedLong` / `Long.toUnsignedString` because a Redis sequence number is an unsigned 64-bit value.

- [ ] **Step 1: Write the failing validation test**

Create `conduit-server/src/test/java/dev/conduit/server/replay/ReplayRequestTest.java`:

```java
package dev.conduit.server.replay;

import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ReplayRequest 校验单元测试：必填、取值范围、from < to、跨度上限 6 小时。 */
class ReplayRequestTest {

    private static void expectBadRequest(Long from, Long to, String messagePart) {
        assertThatThrownBy(() -> new ReplayRequest(from, to).validated())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(messagePart);
    }

    @Test
    void acceptsARangeWithinTheSpanLimit() {
        ReplayRequest request = new ReplayRequest(1760400000000L, 1760400000000L + ReplayRequest.MAX_SPAN_MILLIS);

        assertThat(request.validated()).isSameAs(request);
    }

    @Test
    void rejectsMissingBounds() {
        expectBadRequest(null, 1760400000000L, "from");
        expectBadRequest(1760400000000L, null, "to");
    }

    @Test
    void rejectsAnEmptyOrInvertedRange() {
        expectBadRequest(1760400000000L, 1760400000000L, "from 必须小于 to");
        expectBadRequest(1760400000001L, 1760400000000L, "from 必须小于 to");
    }

    @Test
    void rejectsBoundsOutsideTheAllowedMillisecondRange() {
        expectBadRequest(-1L, 1760400000000L, "from 超出范围");
        expectBadRequest(0L, ReplayRequest.MAX_MILLIS + 1, "to 超出范围");
    }

    @Test
    void rejectsASpanLongerThanSixHours() {
        expectBadRequest(0L, ReplayRequest.MAX_SPAN_MILLIS + 1, "6 小时");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=ReplayRequestTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class ReplayRequest`.

- [ ] **Step 3: Create the request, the state enum and the status**

Create `conduit-server/src/main/java/dev/conduit/server/replay/ReplayRequest.java`:

```java
package dev.conduit.server.replay;

import dev.conduit.server.web.ApiException;

/**
 * 重放请求：毫秒时间范围，左闭右开（from 含、to 不含），与设备列表的时间筛选约定一致。
 * 跨度上限用于防止把 from 误写成 0 而重灌整条流。
 */
public record ReplayRequest(Long from, Long to) {

    /** 允许的最大毫秒值（9999-12-31T23:59:59.999Z），与设备列表时间筛选相同。 */
    public static final long MAX_MILLIS = 253402300799999L;

    /** 单次重放允许的最大跨度：6 小时。 */
    public static final long MAX_SPAN_MILLIS = 6L * 60 * 60 * 1000;

    /**
     * 校验取值并返回自身，便于链式调用。
     *
     * @throws ApiException 400：from / to 缺失、超出 0～{@link #MAX_MILLIS}、from 不小于 to，或跨度超过 6 小时
     */
    public ReplayRequest validated() {
        if (from == null) {
            throw ApiException.badRequest("from 必填");
        }
        if (to == null) {
            throw ApiException.badRequest("to 必填");
        }
        checkBound("from", from);
        checkBound("to", to);
        if (from >= to) {
            throw ApiException.badRequest("from 必须小于 to");
        }
        if (to - from > MAX_SPAN_MILLIS) {
            throw ApiException.badRequest("时间跨度不能超过 6 小时");
        }
        return this;
    }

    /** 单个毫秒参数必须落在 0～{@link #MAX_MILLIS} 内。 */
    private static void checkBound(String name, long value) {
        if (value < 0 || value > MAX_MILLIS) {
            throw ApiException.badRequest(name + " 超出范围（0 到 " + MAX_MILLIS + "）");
        }
    }
}
```

Create `conduit-server/src/main/java/dev/conduit/server/replay/ReplayState.java`:

```java
package dev.conduit.server.replay;

/**
 * 重放任务状态。NONE 表示本进程启动以来从未提交过任务；最近一次完成的状态保留到下一次提交为止。
 */
public enum ReplayState {
    /** 从未提交过任务 */
    NONE,
    /** 正在读取并重放 */
    RUNNING,
    /** 正常跑完整个范围 */
    SUCCEEDED,
    /** 因错误中止，error 字段给出原因 */
    FAILED,
    /** 被取消，已完成部分的统计保留 */
    CANCELLED
}
```

Create `conduit-server/src/main/java/dev/conduit/server/replay/ReplayStatus.java`:

```java
package dev.conduit.server.replay;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 重放任务状态快照（三个接口的统一响应）。从未提交过任务时只有 state=NONE，其余字段不输出，
 * 因此用 NON_NULL 序列化。
 *
 * @param scanned  从流中读出的条目数
 * @param written  成功写入时序库的测点消息数
 * @param skipped  解码失败、产品已删除、非属性消息等跳过的条数
 * @param rejected 物模型校验剔除了全部测点的条数（不写死信，仅计数）
 * @param error    FAILED 时的原因，其余状态为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayStatus(ReplayState state, Long from, Long to, Long startedAt, Long finishedAt,
                           Long scanned, Long written, Long skipped, Long rejected, String error) {

    /** 从未提交过任务的状态：{"state":"NONE"}。 */
    public static ReplayStatus none() {
        return new ReplayStatus(ReplayState.NONE, null, null, null, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 4: Run the validation test and confirm it passes**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=ReplayRequestTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing service test**

Create `conduit-server/src/test/java/dev/conduit/server/replay/ReplayServiceTest.java`:

```java
package dev.conduit.server.replay;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;
import dev.conduit.server.pipeline.Backoff;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import dev.conduit.server.pipeline.TelemetryIngestor;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryUnavailableException;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReplayService 单元测试：id 区间与分批、四项计数、取消、同时只允许一个任务（409）、
 * 重试超限后 FAILED，以及「不写死信、不 ACK」。
 */
class ReplayServiceTest {

    private static final long FROM = 1760400000000L;
    private static final long TO = FROM + 1000;
    private static final long NOW = 1760400009999L;

    /** 收下 Runnable 但不执行，由测试自己决定何时跑，使取消与 409 路径可确定地断言。 */
    private static final class PendingExecutor implements Executor {
        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            List<Runnable> copy = List.copyOf(pending);
            pending.clear();
            copy.forEach(Runnable::run);
        }
    }

    MessageBus bus = mock(MessageBus.class);
    TelemetryIngestor ingestor = mock(TelemetryIngestor.class);
    Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    Backoff backoff = new Backoff(Duration.ofMillis(1), Duration.ofMillis(2), 3);

    @BeforeEach
    void setUp() {
        when(ingestor.write(anyList())).thenReturn(WriteResult.ok());
        when(bus.range(anyString(), anyString(), anyInt())).thenReturn(List.of());
    }

    /** 同步执行的服务：start 返回时任务已经跑完。 */
    private ReplayService syncService() {
        return new ReplayService(bus, ingestor, clock, backoff, Runnable::run);
    }

    private static Delivery property(String id, String deviceKey) {
        RawMessage m = new RawMessage("pk", deviceKey, MessageType.PROPERTY,
                "{}".getBytes(StandardCharsets.UTF_8), FROM, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 0);
    }

    private static Delivery presence(String id, String deviceKey) {
        RawMessage m = new RawMessage("pk", deviceKey, MessageType.PRESENCE_CONNECTED,
                new byte[0], FROM, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 0);
    }

    private static Delivery broken(String id) {
        return new Delivery(id, Map.of("dk", "d1"), 0);
    }

    private static TelemetryPoint point(String deviceKey) {
        return new TelemetryPoint(1L, "pk", deviceKey, FROM, Map.of("Ua", 220.5));
    }

    private void prepares(String deviceKey) {
        when(ingestor.prepare(anyString(), any())).thenReturn(
                new TelemetryIngestor.Prepared(point(deviceKey), List.of()));
    }

    /** 第一批返回 batch，之后返回空，模拟读完整个范围。 */
    private void readsOneBatch(List<Delivery> batch) {
        when(bus.range(anyString(), anyString(), anyInt())).thenReturn(batch).thenReturn(List.of());
    }

    @Test
    void statusIsNoneBeforeAnyTask() {
        assertThat(syncService().status()).isEqualTo(ReplayStatus.none());
    }

    @Test
    void readsTheHalfOpenRangeAsAnInclusiveIdRange() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");

        syncService().start(new ReplayRequest(FROM, TO));

        verify(bus).range("1760400000000-0", "1760400000999-18446744073709551615", 500);
    }

    @Test
    void continuesAfterTheLastEntryOfEachBatch() {
        when(bus.range(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(property("1760400000000-7", "d1")))
                .thenReturn(List.of());
        prepares("d1");

        syncService().start(new ReplayRequest(FROM, TO));

        verify(bus).range("1760400000000-8", "1760400000999-18446744073709551615", 500);
    }

    @Test
    void startReturnsTheSnapshotTakenAtSubmission() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");

        ReplayStatus status = new ReplayService(bus, ingestor, clock, backoff, new PendingExecutor())
                .start(new ReplayRequest(FROM, TO));

        assertThat(status.state()).isEqualTo(ReplayState.RUNNING);
        assertThat(status.from()).isEqualTo(FROM);
        assertThat(status.to()).isEqualTo(TO);
        assertThat(status.startedAt()).isEqualTo(NOW);
        assertThat(status.finishedAt()).isNull();
        assertThat(status.scanned()).isZero();
    }

    @Test
    void countsScannedWrittenSkippedAndRejected() {
        readsOneBatch(List.of(
                property("1760400000000-0", "d1"),
                presence("1760400000001-0", "d1"),
                broken("1760400000002-0"),
                property("1760400000003-0", "d2")));
        when(ingestor.prepare(anyString(), any()))
                .thenReturn(new TelemetryIngestor.Prepared(point("d1"), List.of()))
                .thenThrow(new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected"));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));
        ReplayStatus status = service.status();

        assertThat(status.state()).isEqualTo(ReplayState.SUCCEEDED);
        assertThat(status.scanned()).isEqualTo(4);
        assertThat(status.written()).isEqualTo(1);
        assertThat(status.skipped()).isEqualTo(2);
        assertThat(status.rejected()).isEqualTo(1);
        assertThat(status.from()).isEqualTo(FROM);
        assertThat(status.to()).isEqualTo(TO);
        assertThat(status.startedAt()).isEqualTo(NOW);
        assertThat(status.finishedAt()).isEqualTo(NOW);
        assertThat(status.error()).isNull();
    }

    @Test
    void countsADecodeFailureThatIsNotAFieldRejectionAsSkipped() {
        readsOneBatch(List.of(property("1760400000000-0", "ghost")));
        when(ingestor.prepare(anyString(), any()))
                .thenThrow(new DecodeException(DeadReason.UNKNOWN_DEVICE, "ghost"));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        assertThat(service.status().skipped()).isEqualTo(1);
        assertThat(service.status().rejected()).isZero();
    }

    @Test
    void countsPointsThatInfluxRejectsAsSkipped() {
        readsOneBatch(List.of(property("1760400000000-0", "d1"), property("1760400000001-0", "d1")));
        prepares("d1");
        when(ingestor.write(anyList()))
                .thenReturn(new WriteResult(List.of(new WriteResult.Rejected(1, "type conflict"))));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        assertThat(service.status().written()).isEqualTo(1);
        assertThat(service.status().skipped()).isEqualTo(1);
    }

    @Test
    void neverWritesDeadLettersAndNeverAcks() {
        readsOneBatch(List.of(property("1760400000000-0", "ghost")));
        when(ingestor.prepare(anyString(), any()))
                .thenThrow(new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected"));

        syncService().start(new ReplayRequest(FROM, TO));

        verify(bus, never()).recordDead(any(), anyString(), any());
        verify(bus, never()).deadLetter(anyString(), any(), anyString());
        verify(bus, never()).ack(anyString(), anyList());
    }

    @Test
    void refusesASecondTaskWhileOneIsRunning() {
        PendingExecutor executor = new PendingExecutor();
        ReplayService service = new ReplayService(bus, ingestor, clock, backoff, executor);
        service.start(new ReplayRequest(FROM, TO));

        assertThatThrownBy(() -> service.start(new ReplayRequest(FROM, TO)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void acceptsANewTaskOnceThePreviousOneHasFinished() {
        readsOneBatch(List.of());
        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        assertThat(service.start(new ReplayRequest(FROM, TO)).state()).isEqualTo(ReplayState.RUNNING);
    }

    @Test
    void cancellingStopsTheTaskAndKeepsTheStatisticsSoFar() {
        PendingExecutor executor = new PendingExecutor();
        ReplayService service = new ReplayService(bus, ingestor, clock, backoff, executor);
        service.start(new ReplayRequest(FROM, TO));

        service.cancel();
        executor.runAll();

        ReplayStatus status = service.status();
        assertThat(status.state()).isEqualTo(ReplayState.CANCELLED);
        assertThat(status.scanned()).isZero();
        assertThat(status.finishedAt()).isEqualTo(NOW);
        verify(bus, never()).range(anyString(), anyString(), anyInt());
    }

    @Test
    void cancellingWithoutARunningTaskIsAConflict() {
        assertThatThrownBy(() -> syncService().cancel())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void failsWithAReasonWhenTheWriteKeepsFailing() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");
        when(ingestor.write(anyList()))
                .thenThrow(new TelemetryUnavailableException(new RuntimeException("influx down")));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        ReplayStatus status = service.status();
        assertThat(status.state()).isEqualTo(ReplayState.FAILED);
        assertThat(status.error()).contains("influx down");
        assertThat(status.finishedAt()).isEqualTo(NOW);
    }
}
```

- [ ] **Step 6: Run it and confirm it fails**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=ReplayServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class ReplayService`.

- [ ] **Step 7: Create `ReplayTask`**

Create `conduit-server/src/main/java/dev/conduit/server/replay/ReplayTask.java`:

```java
package dev.conduit.server.replay;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次重放任务的运行时状态：时间范围、四项计数、取消标志与结束信息。
 * 执行线程写、请求线程读，因此计数用原子类型、状态字段用 volatile；状态不持久化（spec 4.2）。
 */
class ReplayTask {

    private final long from;
    private final long to;
    private final long startedAt;
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private volatile ReplayState state = ReplayState.RUNNING;
    private volatile Long finishedAt;
    private volatile String error;

    ReplayTask(long from, long to, long startedAt) {
        this.from = from;
        this.to = to;
        this.startedAt = startedAt;
    }

    long from() {
        return from;
    }

    long to() {
        return to;
    }

    /** 任务是否仍在运行（决定能否提交新任务、能否取消）。 */
    boolean isRunning() {
        return state == ReplayState.RUNNING;
    }

    /** 置取消标志；执行线程在下一个批次之间看到后停止。 */
    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    void addScanned(long n) {
        scanned.addAndGet(n);
    }

    void addWritten(long n) {
        written.addAndGet(n);
    }

    void addSkipped(long n) {
        skipped.addAndGet(n);
    }

    void addRejected(long n) {
        rejected.addAndGet(n);
    }

    /**
     * 结束任务并记录结果；已完成部分的计数保留。
     *
     * @param error FAILED 时的原因，其余状态传 null
     */
    void finish(ReplayState finalState, String error, long finishedAt) {
        this.error = error;
        this.finishedAt = finishedAt;
        this.state = finalState;
    }

    /** 当前状态的快照，供 REST 接口返回。 */
    ReplayStatus snapshot() {
        return new ReplayStatus(state, from, to, startedAt, finishedAt,
                scanned.get(), written.get(), skipped.get(), rejected.get(), error);
    }
}
```

- [ ] **Step 8: Create `ReplayService`**

Create `conduit-server/src/main/java/dev/conduit/server/replay/ReplayService.java`:

```java
package dev.conduit.server.replay;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.pipeline.Backoff;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import dev.conduit.server.pipeline.ProcessingAbortedException;
import dev.conduit.server.pipeline.TelemetryIngestor;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 遥测重放（V0.2-R3 spec 4）：把总线上行流中保留的原始报文按时间范围重新写入时序库，用于 InfluxDB 断电丢数后的恢复。
 * 同一时刻只允许一个任务，状态保存在内存中的单个 AtomicReference，不持久化；最近一次完成的状态保留到下一次提交为止。
 * 与正常流水线共用 {@link TelemetryIngestor}，但<b>不更新在线状态、不写死信、不 ACK</b>——重放的是旧数据，
 * 会把已离线设备标成在线，死信也会被重复写入污染首页统计。写入幂等（同时间戳覆盖、最新值按 ts 比较），
 * 所以重放可以与正常消费并行，重复重放也无害。
 */
@Service
public class ReplayService {

    /** 每批从流中读取的条目数（spec 4.3）。 */
    static final int BATCH_SIZE = 500;

    /**
     * 写库等可恢复错误的最大尝试次数：配合 1s→30s 退避，约 15 秒后仍不可用就把任务置为 FAILED。
     * 重放是人工发起的恢复动作，失败后重新提交即可，因此不做成配置项。
     */
    static final int MAX_ATTEMPTS = 5;

    /** Redis 流条目序号是无符号 64 位整数，取最大值作为「某毫秒内的最后一条」。 */
    private static final String MAX_SEQUENCE = "18446744073709551615";

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final MessageBus bus;
    private final TelemetryIngestor ingestor;
    private final Clock clock;
    private final Backoff backoff;
    private final Executor executor;
    private final AtomicReference<ReplayTask> current = new AtomicReference<>();

    @Autowired
    public ReplayService(MessageBus bus, TelemetryIngestor ingestor, Clock clock) {
        this(bus, ingestor, clock, new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), MAX_ATTEMPTS),
                command -> Thread.ofVirtual().name("telemetry-replay").start(command));
    }

    /** 可替换退避与执行器的构造器，测试用（同步执行或先收下再执行，便于确定地断言取消与 409）。 */
    ReplayService(MessageBus bus, TelemetryIngestor ingestor, Clock clock, Backoff backoff, Executor executor) {
        this.bus = bus;
        this.ingestor = ingestor;
        this.clock = clock;
        this.backoff = backoff;
        this.executor = executor;
    }

    /**
     * 提交一次重放，立即返回提交那一刻的状态（RUNNING），实际执行在后台线程。
     *
     * @throws ApiException 400 参数非法（见 {@link ReplayRequest#validated()}）；409 已有任务正在运行
     */
    public ReplayStatus start(ReplayRequest request) {
        ReplayRequest valid = request.validated();
        ReplayTask existing = current.get();
        if (existing != null && existing.isRunning()) {
            throw ApiException.conflict("已有重放任务正在运行");
        }
        ReplayTask task = new ReplayTask(valid.from(), valid.to(), clock.millis());
        if (!current.compareAndSet(existing, task)) {
            throw ApiException.conflict("已有重放任务正在运行");
        }
        log.info("Telemetry replay submitted: from={} to={}", valid.from(), valid.to());
        executor.execute(() -> run(task));
        return task.snapshot();
    }

    /** 当前（或最近一次）任务的状态；从未提交过返回 {@link ReplayStatus#none()}。 */
    public ReplayStatus status() {
        ReplayTask task = current.get();
        return task == null ? ReplayStatus.none() : task.snapshot();
    }

    /**
     * 请求取消正在运行的任务：只置标志，执行线程在下一个批次之间停止。
     *
     * @throws ApiException 409 没有正在运行的任务
     */
    public ReplayStatus cancel() {
        ReplayTask task = current.get();
        if (task == null || !task.isRunning()) {
            throw ApiException.conflict("没有正在运行的重放任务");
        }
        task.cancel();
        log.info("Telemetry replay cancellation requested");
        return task.snapshot();
    }

    /**
     * 执行一次重放：按 id 区间分批 XRANGE 读取并处理，每批之间检查取消标志。
     * 区间左闭右开地映射为两端都含的 id 区间：[from-0, (to-1)-最大序号]。
     * 可恢复错误由 {@link Backoff} 原地重试，超过上限后任务置为 FAILED 并记录原因。
     */
    void run(ReplayTask task) {
        String startId = task.from() + "-0";
        String endId = (task.to() - 1) + "-" + MAX_SEQUENCE;
        try {
            while (true) {
                if (task.isCancelled()) {
                    task.finish(ReplayState.CANCELLED, null, clock.millis());
                    log.info("Telemetry replay cancelled: {}", task.snapshot());
                    return;
                }
                List<Delivery> batch = bus.range(startId, endId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                task.addScanned(batch.size());
                log.debug("Replaying {} entries from {}", batch.size(), startId);
                replayBatch(task, batch);
                startId = nextId(batch.getLast().id());
            }
            task.finish(ReplayState.SUCCEEDED, null, clock.millis());
            log.info("Telemetry replay finished: {}", task.snapshot());
        } catch (ProcessingAbortedException e) {
            task.finish(ReplayState.CANCELLED, null, clock.millis());
            log.info("Telemetry replay interrupted, marked as cancelled");
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            task.finish(ReplayState.FAILED, reason, clock.millis());
            log.warn("Telemetry replay failed: {}", reason);
        }
    }

    /**
     * 重放一批条目：逐条解码校验攒成数据点，整批写时序库，再逐条写最新值。
     * 信封损坏、非属性消息与解码失败计入 skipped；全部测点被剔除计入 rejected（不写死信）；
     * 被 InfluxDB 拒绝的数据点（字段类型冲突）计入 skipped；其余计入 written。
     * 部分测点被剔除的消息照常写入已通过的测点，计为 written。
     */
    private void replayBatch(ReplayTask task, List<Delivery> batch) {
        List<TelemetryPoint> points = new ArrayList<>();
        for (Delivery delivery : batch) {
            RawMessage message;
            try {
                message = delivery.message();
            } catch (IllegalArgumentException e) {
                task.addSkipped(1);
                continue;
            }
            if (message.type() != MessageType.PROPERTY) {
                task.addSkipped(1);
                continue;
            }
            try {
                points.add(backoff.run("replay prepare", () -> ingestor.prepare(delivery.id(), message)).point());
            } catch (DecodeException e) {
                if (e.reason() == DeadReason.FIELD_REJECTED) {
                    task.addRejected(1);
                } else {
                    task.addSkipped(1);
                }
            }
        }
        if (points.isEmpty()) {
            return;
        }
        WriteResult result = backoff.run("replay influx write", () -> ingestor.write(points));
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());
        for (int i = 0; i < points.size(); i++) {
            if (rejected.contains(i)) {
                task.addSkipped(1);
                continue;
            }
            TelemetryPoint point = points.get(i);
            backoff.run("replay latest update", () -> {
                ingestor.updateLatest(point);
                return null;
            });
            task.addWritten(1);
        }
    }

    /**
     * 下一批的起始 id：同一毫秒内序号加一。序号是无符号 64 位，用无符号解析与格式化。
     *
     * @param lastId 本批最后一条的 id，形如 {@code 1760400000000-7}
     */
    private static String nextId(String lastId) {
        int dash = lastId.indexOf('-');
        String millis = lastId.substring(0, dash);
        long sequence = Long.parseUnsignedLong(lastId.substring(dash + 1));
        return millis + "-" + Long.toUnsignedString(sequence + 1);
    }
}
```

- [ ] **Step 9: Run the service test and confirm it passes**

Run: `mvn -B -q -pl conduit-server -am test -Dtest=ReplayServiceTest,ReplayRequestTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both classes pass — `ReplayServiceTest` runs 13 tests, `ReplayRequestTest` 5, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add conduit-server/src/main/java/dev/conduit/server/replay conduit-server/src/test/java/dev/conduit/server/replay
git commit -m "feat(replay): add the telemetry replay task state and execution loop"
```

---

## Task 4: The three endpoints and the integration tests that prove the spec's acceptance criteria

**Files:**
- Create: `conduit-server/src/main/java/dev/conduit/server/replay/ReplayController.java`
- Test: `conduit-server/src/test/java/dev/conduit/server/replay/ReplayApiIT.java` (new)
- Test: `conduit-server/src/test/java/dev/conduit/server/replay/ReplayIT.java` (new)

**Interfaces:**
- Consumes: `ReplayService.start/status/cancel` and `ReplayRequest` / `ReplayStatus` (Task 3); `MessageBus.publish` (to put entries on the stream in tests); `IntegrationTestBase` helpers `createDevice`, `defineProperty`, `loginAsPlatformAdmin`, `login`, `createTenant`, `createUser`, `unique`, `mvc`, `redis`; `InfluxTestSupport.awaitCount/count`; `PresenceStore.apply/get`; `Presence(boolean online, long since, String clientIp)`.
- Produces (Task 5 relies on this HTTP contract):
  - `POST /api/v1/platform/telemetry/replay` with body `{"from":<millis>,"to":<millis>}` → 200 and the status JSON; 400 `problem+json` on invalid input; 409 when a task is already running.
  - `GET /api/v1/platform/telemetry/replay` → 200 and the status JSON; `{"state":"NONE"}` when nothing has run in this process.
  - `DELETE /api/v1/platform/telemetry/replay` → 200 and the status JSON; 409 when nothing is running.
  - All three refuse tenant admins, tenant viewers and impersonating platform admins with 403 (`SecurityConfig`, unchanged).

**Decisions recorded here:**
- No new security rule. `/api/v1/platform/**` is already `hasRole(PLATFORM_ADMIN)` and an impersonation token's effective role is `TENANT_ADMIN`, so impersonating admins are refused as well. `ReplayApiIT` asserts this instead of assuming it.
- A `POST` with no body or unparsable JSON is turned into 400 by the inherited `ResponseEntityExceptionHandler`, so the controller needs no extra branch. Every other 400 comes from `ReplayRequest.validated()` as `problem+json`, matching the device-filter style.
- `ReplayIT` does not clear the stream. Spring test contexts are shared across test classes and the up-stream key `test:up` is shared, so deleting it could break another class's consumer group. Instead each test captures `System.currentTimeMillis()` immediately before publishing and uses `[start, now+1)` as the range. Surefire/Failsafe run test classes sequentially, so that window holds only this test's entries, and the counters can be asserted exactly.
- `ReplayApiIT` does not assert `{"state":"NONE"}` for the same reason — another class in the same context may have run a replay first. The `NONE` case is covered by `ReplayServiceTest.statusIsNoneBeforeAnyTask`, which starts from a fresh service.
- The test profile sets `conduit.pipeline.enabled: false`, so nothing consumes the stream while these tests run: entries published in a test stay there for the replay to read.

- [ ] **Step 1: Write the failing API test**

Create `conduit-server/src/test/java/dev/conduit/server/replay/ReplayApiIT.java`:

```java
package dev.conduit.server.replay;

import dev.conduit.server.auth.Roles;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 遥测重放接口集成测试：参数校验（400）、没有运行中任务时取消返回 409、状态查询可用，
 * 以及只有未代入的平台管理员能访问这三个接口。
 */
class ReplayApiIT extends IntegrationTestBase {

    private static final String PATH = "/api/v1/platform/telemetry/replay";

    private String sys;

    @BeforeEach
    void setUp() throws Exception {
        sys = loginAsPlatformAdmin();
    }

    /** 以平台管理员提交一次重放，返回响应以便断言状态码与响应体。 */
    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 某身份访问某方法时的状态码，用来断言权限矩阵。 */
    private int statusOf(String token, HttpMethod method) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (method == HttpMethod.POST) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{\"from\":0,\"to\":1000}");
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    void rejectsAnInvertedOrEmptyRange() throws Exception {
        submit("{\"from\":1760400000000,\"to\":1760400000000}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from 必须小于 to"));
        submit("{\"from\":1760400001000,\"to\":1760400000000}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingBoundsAndAnEmptyBody() throws Exception {
        submit("{\"from\":1760400000000}").andExpect(status().isBadRequest());
        submit("{}").andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBoundsOutsideTheAllowedRange() throws Exception {
        submit("{\"from\":-1,\"to\":1000}").andExpect(status().isBadRequest());
        submit("{\"from\":0,\"to\":253402300800000}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsASpanLongerThanSixHours() throws Exception {
        submit("{\"from\":0,\"to\":21600001}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("时间跨度不能超过 6 小时"));
    }

    @Test
    void cancellingWithoutARunningTaskIsAConflict() throws Exception {
        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("没有正在运行的重放任务"));
    }

    @Test
    void theStatusEndpointAnswersThePlatformAdmin() throws Exception {
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").exists());
    }

    @Test
    void tenantRolesCannotReachAnyOfTheThreeEndpoints() throws Exception {
        long tenantId = createTenant("rp-");
        String admin = login(createUser(tenantId, Roles.TENANT_ADMIN, false), TEST_PASSWORD);
        String viewer = login(createUser(tenantId, Roles.TENANT_VIEWER, false), TEST_PASSWORD);

        for (String token : new String[]{admin, viewer}) {
            assertThat(statusOf(token, HttpMethod.GET)).isEqualTo(403);
            assertThat(statusOf(token, HttpMethod.POST)).isEqualTo(403);
            assertThat(statusOf(token, HttpMethod.DELETE)).isEqualTo(403);
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test=ReplayApiIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: every test that calls the endpoint fails with 404 (no controller is mapped yet); the permission test may pass by accident, which is fine.

- [ ] **Step 3: Create the controller**

Create `conduit-server/src/main/java/dev/conduit/server/replay/ReplayController.java`:

```java
package dev.conduit.server.replay;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测重放接口（/api/v1/platform/telemetry/replay，仅未代入的平台管理员，权限由 SecurityConfig 的
 * /api/v1/platform/** 规则统一限定）。同一时刻只允许一个任务，因此查询与取消都不带任务 id。
 * 逻辑在 {@link ReplayService}。
 */
@RestController
@RequestMapping("/api/v1/platform/telemetry/replay")
public class ReplayController {

    private final ReplayService service;

    public ReplayController(ReplayService service) {
        this.service = service;
    }

    /**
     * 提交重放，立即返回提交那一刻的状态（RUNNING）。
     *
     * @throws dev.conduit.server.web.ApiException 400 参数非法；409 已有任务正在运行
     */
    @PostMapping
    public ReplayStatus start(@RequestBody ReplayRequest request) {
        return service.start(request);
    }

    /** 查询当前（或最近一次）任务状态；从未提交过返回 {"state":"NONE"}。 */
    @GetMapping
    public ReplayStatus status() {
        return service.status();
    }

    /**
     * 请求取消正在运行的任务。
     *
     * @throws dev.conduit.server.web.ApiException 409 没有正在运行的任务
     */
    @DeleteMapping
    public ReplayStatus cancel() {
        return service.cancel();
    }
}
```

- [ ] **Step 4: Run the API test and confirm it passes**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test=ReplayApiIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing end-to-end replay test**

Create `conduit-server/src/test/java/dev/conduit/server/replay/ReplayIT.java`:

```java
package dev.conduit.server.replay;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.InfluxClient;
import dev.conduit.server.telemetry.LatestValue;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 遥测重放端到端集成测试：把总线中保留的原始报文重新写入时序库与最新值、不把离线设备标成在线、
 * 物模型全部测点被拒时只计数不写死信。
 * 测试时流水线未启用（application-test.yml 中 conduit.pipeline.enabled=false），所以写入总线的条目不会被消费掉。
 */
class ReplayIT extends IntegrationTestBase {

    private static final String PATH = "/api/v1/platform/telemetry/replay";

    @Autowired
    MessageBus bus;

    @Autowired
    InfluxClient influx;

    @Autowired
    LatestValueStore latest;

    @Autowired
    PresenceStore presence;

    TestDevice device;
    String sys;
    long windowStart;

    @BeforeEach
    void setUp() throws Exception {
        device = createDevice(defaultTenantId(), "ENABLED");
        sys = loginAsPlatformAdmin();
        windowStart = System.currentTimeMillis();
    }

    /** 把一条属性报文写进上行流；ts 为设备时间戳。 */
    private void publish(String payload, long receivedAt) {
        bus.publish(new RawMessage(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                payload.getBytes(StandardCharsets.UTF_8), receivedAt, Map.of()));
    }

    /**
     * 提交覆盖本测试已写入条目的重放（窗口为 [setUp 时刻, 现在+1)，测试类串行执行，窗口内只有本测试的条目），
     * 等任务结束后返回最终状态 JSON。
     */
    private String replayAndWait() throws Exception {
        long to = System.currentTimeMillis() + 1;
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":%d,\"to\":%d}".formatted(windowStart, to)))
                .andExpect(status().isOk());
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(this::currentStatus, body -> !"RUNNING".equals(JsonPath.read(body, "$.state")));
    }

    /** 查询当前任务状态的响应体。 */
    private String currentStatus() throws Exception {
        return mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void replaysRawMessagesIntoInfluxAndLatestValues() throws Exception {
        long ts = windowStart - 60_000;
        publish("{\"ts\":%d,\"params\":{\"Ua\":220.5}}".formatted(ts), windowStart);
        publish("{\"ts\":%d,\"params\":{\"Ua\":221.5}}".formatted(ts + 1000), windowStart);

        String status = replayAndWait();

        assertThat((String) JsonPath.read(status, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(status, "$.scanned")).isEqualTo(2);
        assertThat((int) JsonPath.read(status, "$.written")).isEqualTo(2);
        assertThat((int) JsonPath.read(status, "$.skipped")).isZero();
        assertThat((int) JsonPath.read(status, "$.rejected")).isZero();
        awaitCount(influx, device.deviceKey(), "Ua", 2);
        assertThat(latest.get(device.deviceKey()))
                .containsEntry("Ua", new LatestValue(221.5, ts + 1000));
    }

    @Test
    void doesNotBringAnOfflineDeviceBackOnline() throws Exception {
        long offlineSince = windowStart - 600_000;
        presence.apply(device.deviceKey(), false, offlineSince, "10.1.2.3");
        publish("{\"ts\":%d,\"params\":{\"Ua\":220.5}}".formatted(windowStart - 60_000), windowStart);

        String status = replayAndWait();

        assertThat((String) JsonPath.read(status, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(status, "$.written")).isEqualTo(1);
        Optional<Presence> after = presence.get(device.deviceKey());
        assertThat(after).contains(new Presence(false, offlineSince, "10.1.2.3"));
    }

    @Test
    void countsFullyRejectedMessagesWithoutWritingDeadLetters() throws Exception {
        defineProperty(device.productKey(), "Ua", "DOUBLE", null, null);
        Long deadBefore = redis.opsForStream().size("test:dead");
        publish("{\"ts\":%d,\"params\":{\"Ua\":\"abc\"}}".formatted(windowStart - 60_000), windowStart);

        String status = replayAndWait();

        assertThat((String) JsonPath.read(status, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(status, "$.rejected")).isEqualTo(1);
        assertThat((int) JsonPath.read(status, "$.written")).isZero();
        assertThat(redis.opsForStream().size("test:dead")).isEqualTo(deadBefore);
    }
}
```

- [ ] **Step 6: Run it and confirm it fails or passes for the right reason**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test=ReplayIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: all three tests pass, because Tasks 1–3 and Step 3 already implemented the behaviour they describe. If `replaysRawMessagesIntoInfluxAndLatestValues` reports `scanned` larger than 2, another test class published into the same millisecond window — rerun the class alone; if it persists, stop and report.

- [ ] **Step 7: Run the whole replay and pipeline test set together**

Run: `mvn -B -pl conduit-server -am verify -Dtest=NONE -Dit.test=ReplayIT,ReplayApiIT,PipelineIT,PermissionMatrixIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: all four classes pass. `PermissionMatrixIT` confirms the new path did not change the existing matrix.

- [ ] **Step 8: Commit**

```bash
git add conduit-server/src/main/java/dev/conduit/server/replay conduit-server/src/test/java/dev/conduit/server/replay
git commit -m "feat(replay): expose the telemetry replay endpoints"
```

---

## Task 5: The「数据重放」page, its route and menu entry

**Files:**
- Modify: `conduit-ui/src/api/types.ts` (append three types)
- Create: `conduit-ui/src/api/replay.ts`
- Create: `conduit-ui/src/views/TelemetryReplayView.vue`
- Create: `conduit-ui/src/views/TelemetryReplayView.spec.ts`
- Modify: `conduit-ui/src/router/meta.ts` (add `telemetry-replay`)
- Modify: `conduit-ui/src/router/index.ts` (add the route)
- Modify: `conduit-ui/src/layouts/menu.ts` (platform admin's 系统管理 group)
- Modify: `conduit-ui/src/layouts/menu.spec.ts` (updated expectation)
- Modify: `conduit-ui/src/test/viewTestUtils.ts` (same route in the in-memory router)

**Interfaces:**
- Consumes: the HTTP contract from Task 4; `http` from `@/api/client`; `problemMessage` from `@/api/errors`; `startPolling(task, intervalMs) => () => void` from `@/composables/usePolling`; `PageHeader` with props `title` / `subtitle`; `ROUTE_META` from `@/router/meta`; `createTestPinia` / `createTestRouter` / `settle` from `@/test/viewTestUtils`.
- Produces:
  - `export type ReplayState = 'NONE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'`
  - `export interface ReplayStatus { state: ReplayState; from?: number; to?: number; startedAt?: number; finishedAt?: number; scanned?: number; written?: number; skipped?: number; rejected?: number; error?: string }`
  - `export interface ReplayRequest { from: number; to: number }`
  - `export const replayApi = { status(), start(req), cancel() }`
  - Route name `telemetry-replay` at path `/telemetry-replay`.

**Decisions recorded here:**
- The page has no table, so `useTable`, `FilterBar` and the 10-rows-per-page rule do not apply. It uses `PageHeader`, Element Plus form controls and CSS variables only, as CLAUDE.md §2.2 requires for every page.
- All optional status fields are declared optional in TypeScript because the backend omits them for `{"state":"NONE"}`.
- Polling is driven by `startPolling` from the existing composable: it is started only while the state is `RUNNING`, stopped as soon as a poll reports anything else, and stopped again in `onBeforeUnmount`, which covers「离开页面时清除定时器」.
- The 6-hour check happens before the request so the user gets a Chinese hint instead of a backend 400, exactly as the spec asks; the backend check stays authoritative.
- Write actions here are not gated by `usePermission().canWrite` (that flag means「租户管理员」and is false for a platform admin). The page is platform-only through `meta.scope: 'platform'`, which the router guard and `AppLayout` enforce, and the backend enforces the real permission.

- [ ] **Step 1: Write the failing page test**

Create `conduit-ui/src/views/TelemetryReplayView.spec.ts`:

```ts
/**
 * TelemetryReplayView 组件测试：从未提交任务时的空状态、跨度超过 6 小时时前端拦截、
 * 运行中轮询并显示取消按钮、任务结束后停止轮询、离开页面清除定时器。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TelemetryReplayView from './TelemetryReplayView.vue'
import { replayApi } from '@/api/replay'
import type { ReplayStatus } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/replay', () => ({
  replayApi: { status: vi.fn(), start: vi.fn(), cancel: vi.fn() },
}))
vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
// 确认框直接确认
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } }
})

const START = 1760400000000

function status(patch: Partial<ReplayStatus> = {}): ReplayStatus {
  return {
    state: 'RUNNING',
    from: START,
    to: START + 3600_000,
    startedAt: START,
    scanned: 10,
    written: 9,
    skipped: 1,
    rejected: 0,
    ...patch,
  }
}

type Vm = {
  range: [Date, Date] | null
  start(): Promise<void>
  cancel(): Promise<void>
}

async function mountView() {
  const pinia = createTestPinia({ username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })
  const router = createTestRouter()
  await router.push('/telemetry-replay')
  await router.isReady()
  const wrapper = mount(TelemetryReplayView, {
    global: { plugins: [pinia, router, ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('TelemetryReplayView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.mocked(replayApi.status).mockResolvedValue({ state: 'NONE' })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows an empty state when no replay has ever run', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.find('.el-empty').exists()).toBe(true)
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })

  it('refuses a range longer than six hours without calling the backend', async () => {
    const { vm } = await mountView()
    vm.range = [new Date(START), new Date(START + 6 * 3600_000 + 60_000)]

    await vm.start()

    expect(replayApi.start).not.toHaveBeenCalled()
  })

  it('submits the chosen range and keeps polling while the task runs', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    // 从这一刻起状态查询都返回「进行中」，轮询应持续
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]

    await vm.start()
    await settle()

    expect(replayApi.start).toHaveBeenCalledWith({ from: START, to: START + 3600_000 })
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(true)

    const before = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(2100)

    expect(vi.mocked(replayApi.status).mock.calls.length).toBeGreaterThan(before)
  })

  it('stops polling once the task has finished', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    // 提交后的第一次轮询就报告已完成，轮询应立即停止
    vi.mocked(replayApi.status).mockResolvedValue(status({ state: 'SUCCEEDED', finishedAt: START + 5000 }))
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    const callsAfterFinish = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)

    expect(vi.mocked(replayApi.status).mock.calls.length).toBe(callsAfterFinish)
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })

  it('clears the timer when the page is left', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    wrapper.unmount()
    const callsAtUnmount = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)

    expect(vi.mocked(replayApi.status).mock.calls.length).toBe(callsAtUnmount)
  })

  it('cancels the running task', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    vi.mocked(replayApi.cancel).mockResolvedValue(status({ state: 'CANCELLED', finishedAt: START + 5000 }))
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    await vm.cancel()
    await settle()

    expect(replayApi.cancel).toHaveBeenCalled()
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd conduit-ui && npm run test -- src/views/TelemetryReplayView.spec.ts`
Expected: failure resolving `./TelemetryReplayView.vue` and `@/api/replay`.

- [ ] **Step 3: Add the types and the API module**

Append to `conduit-ui/src/api/types.ts`:

```ts
/** 遥测重放任务状态；NONE 表示服务端本次启动以来从未提交过任务 */
export type ReplayState = 'NONE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/**
 * 遥测重放任务状态。除 state 外的字段在从未提交过任务时不返回，因此都是可选的。
 * scanned 为读出的条目数，written 为成功写入时序库的条数，skipped 为跳过的条数，rejected 为物模型全部测点被拒的条数。
 */
export interface ReplayStatus {
  state: ReplayState
  from?: number
  to?: number
  startedAt?: number
  finishedAt?: number
  scanned?: number
  written?: number
  skipped?: number
  rejected?: number
  error?: string
}

/** 提交重放：毫秒时间范围，左闭右开，跨度不超过 6 小时 */
export interface ReplayRequest {
  from: number
  to: number
}
```

Create `conduit-ui/src/api/replay.ts`:

```ts
/**
 * 遥测重放的 REST 调用（/api/v1/platform/telemetry/replay，仅平台管理员）：
 * 查询当前任务状态、提交重放、请求取消。同一时刻只允许一个任务，因此三个调用都不带任务 id。
 */

import { http } from './client'
import type { ReplayRequest, ReplayStatus } from './types'

const PATH = '/api/v1/platform/telemetry/replay'

export const replayApi = {
  status: () => http.get<ReplayStatus>(PATH).then((r) => r.data),
  start: (req: ReplayRequest) => http.post<ReplayStatus>(PATH, req).then((r) => r.data),
  cancel: () => http.delete<ReplayStatus>(PATH).then((r) => r.data),
}
```

- [ ] **Step 4: Create the page**

Create `conduit-ui/src/views/TelemetryReplayView.vue`:

```vue
<script setup lang="ts">
/**
 * 数据重放（平台管理员）：按时间范围把总线中保留的原始报文重新写入时序库，用于 InfluxDB 断电丢数后的恢复。
 * 跨度上限 6 小时，提交前在前端先行校验并弹确认；任务运行中每 2 秒轮询状态并可取消，任务结束即停止轮询，
 * 离开页面时清除定时器。从未提交过任务时显示空状态。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { replayApi } from '@/api/replay'
import { problemMessage } from '@/api/errors'
import type { ReplayState, ReplayStatus } from '@/api/types'
import { startPolling } from '@/composables/usePolling'
import PageHeader from '@/components/PageHeader.vue'

/** 单次重放允许的最大跨度：6 小时，与服务端一致 */
const MAX_SPAN_MS = 6 * 60 * 60 * 1000
/** 运行中的状态轮询间隔 */
const POLL_MS = 2000

const range = ref<[Date, Date] | null>(null)
const status = ref<ReplayStatus | null>(null)
const submitting = ref(false)
let stopPoll: (() => void) | null = null

/** 任务是否在运行中（决定是否轮询、是否显示取消按钮） */
const running = computed(() => status.value?.state === 'RUNNING')
/** 从未提交过任务：显示空状态与引导文案 */
const empty = computed(() => !status.value || status.value.state === 'NONE')

const STATE_LABELS: Record<ReplayState, string> = {
  NONE: '未开始',
  RUNNING: '进行中',
  SUCCEEDED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

const STATE_TYPES: Record<ReplayState, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  NONE: 'info',
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
}

/** 毫秒时间戳的本地显示；没有值时显示「—」 */
function formatTime(ms?: number): string {
  return ms === undefined ? '—' : new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

/** 计数的显示；没有值时显示「—」 */
function formatCount(n?: number): string {
  return n === undefined ? '—' : String(n)
}

/** 耗时：任务结束后按起止时间算秒数；仍在运行时显示「进行中」 */
const elapsed = computed(() => {
  const s = status.value
  if (!s || s.startedAt === undefined) {
    return '—'
  }
  if (s.finishedAt === undefined) {
    return '进行中'
  }
  return `${((s.finishedAt - s.startedAt) / 1000).toFixed(1)} 秒`
})

/** 停止轮询并清除定时器 */
function stopPolling() {
  stopPoll?.()
  stopPoll = null
}

/** 运行中才轮询：状态变为非运行中立即停表 */
function syncPolling() {
  if (running.value) {
    if (!stopPoll) {
      stopPoll = startPolling(poll, POLL_MS)
    }
  } else {
    stopPolling()
  }
}

/** 轮询一次状态；失败保留上次状态，等下一轮 */
async function poll() {
  try {
    status.value = await replayApi.status()
  } catch {
    return
  }
  if (!running.value) {
    stopPolling()
  }
}

/** 首次进入时读取一次状态，运行中则开始轮询 */
async function load() {
  try {
    status.value = await replayApi.status()
  } catch (e) {
    ElMessage.error(problemMessage(e))
    return
  }
  syncPolling()
}

/** 提交重放：校验时间范围与跨度、弹确认、提交后开始轮询 */
async function start() {
  const picked = range.value
  if (!picked) {
    ElMessage.warning('请选择时间范围')
    return
  }
  const from = picked[0].getTime()
  const to = picked[1].getTime()
  if (from >= to) {
    ElMessage.warning('开始时间必须早于结束时间')
    return
  }
  if (to - from > MAX_SPAN_MS) {
    ElMessage.warning('时间跨度不能超过 6 小时')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将重放 ${formatTime(from)} 到 ${formatTime(to)} 之间的原始报文，重新写入时序库。`,
      '开始重放',
      { confirmButtonText: '开始重放', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    status.value = await replayApi.start({ from, to })
    syncPolling()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    submitting.value = false
  }
}

/** 请求取消正在运行的任务 */
async function cancel() {
  try {
    await ElMessageBox.confirm('取消后已完成部分的统计会保留，未重放的数据不会写入。', '取消重放', {
      confirmButtonText: '取消重放',
      cancelButtonText: '继续运行',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    status.value = await replayApi.cancel()
    syncPolling()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

onMounted(() => {
  void load()
})
onBeforeUnmount(stopPolling)

defineExpose({ range, status, start, cancel })
</script>

<template>
  <div class="replay">
    <PageHeader
      title="数据重放"
      subtitle="把总线中保留的原始报文按时间范围重新写入时序库，用于断电后恢复；同设备同时间戳覆盖为相同值，不更新在线状态，也不重复写死信。"
    />

    <div class="cd-panel form">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :disabled="running"
            data-test="range"
          />
          <span class="hint">单次最多 6 小时，结束时间不含在内。</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" :disabled="running" data-test="start" @click="start">
            开始重放
          </el-button>
          <el-button v-if="running" type="danger" plain data-test="cancel" @click="cancel">取消</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="cd-panel task">
      <el-empty v-if="empty" description="还没有重放过数据">
        <span class="hint">选择一段时间范围后点击「开始重放」，恢复该时间段内丢失的时序数据。</span>
      </el-empty>
      <template v-else-if="status">
        <div class="task-head">
          <span class="task-title">当前任务</span>
          <el-tag :type="STATE_TYPES[status.state]" disable-transitions>{{ STATE_LABELS[status.state] }}</el-tag>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="开始时间">{{ formatTime(status.from) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatTime(status.to) }}</el-descriptions-item>
          <el-descriptions-item label="提交于">{{ formatTime(status.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ elapsed }}</el-descriptions-item>
        </el-descriptions>
        <div class="stats">
          <div class="stat"><span class="stat-label">读取</span><span class="stat-value">{{ formatCount(status.scanned) }}</span></div>
          <div class="stat"><span class="stat-label">写入</span><span class="stat-value">{{ formatCount(status.written) }}</span></div>
          <div class="stat"><span class="stat-label">跳过</span><span class="stat-value">{{ formatCount(status.skipped) }}</span></div>
          <div class="stat"><span class="stat-label">校验剔除</span><span class="stat-value">{{ formatCount(status.rejected) }}</span></div>
        </div>
        <el-alert v-if="status.error" :title="status.error" type="error" :closable="false" show-icon />
      </template>
    </div>
  </div>
</template>

<style scoped>
.form {
  margin-bottom: var(--cd-space-4);
}
.hint {
  margin-left: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 12px;
}
.task-head {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  margin-bottom: var(--cd-space-3);
}
.task-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--cd-space-3);
  margin-top: var(--cd-space-4);
}
.stat {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-1);
}
.stat-label {
  color: var(--cd-text-muted);
  font-size: 12px;
}
.stat-value {
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
</style>
```

- [ ] **Step 5: Register the route, the meta and the menu entry**

In `conduit-ui/src/router/meta.ts`, add this entry to `ROUTE_META` right after `tenants` (keep the trailing comma style of the file):

```ts
  'telemetry-replay': {
    title: '数据重放',
    menu: '/telemetry-replay',
    scope: 'platform',
    breadcrumb: [{ label: '系统管理' }, { label: '数据重放' }],
  },
```

In `conduit-ui/src/router/index.ts`, add this child route right after the `tenants` route:

```ts
      {
        path: 'telemetry-replay',
        name: 'telemetry-replay',
        component: () => import('@/views/TelemetryReplayView.vue'),
        meta: ROUTE_META['telemetry-replay'],
      },
```

In `conduit-ui/src/test/viewTestUtils.ts`, add this route right after the `/tenants` route:

```ts
      {
        path: '/telemetry-replay',
        name: 'telemetry-replay',
        component: Empty,
        meta: ROUTE_META['telemetry-replay'],
      },
```

In `conduit-ui/src/layouts/menu.ts`, change the icon import line to

```ts
import { Box, Cpu, House, OfficeBuilding, RefreshRight, User } from '@element-plus/icons-vue'
```

and replace the platform-admin branch of `menuGroupsFor` with

```ts
  if (p.isPlatformAdmin) {
    return [
      OVERVIEW,
      {
        title: '系统管理',
        items: [
          { path: '/tenants', label: '租户', icon: OfficeBuilding },
          { path: '/telemetry-replay', label: '数据重放', icon: RefreshRight },
        ],
      },
    ]
  }
```

In `conduit-ui/src/layouts/menu.spec.ts`, update the platform-admin expectation to

```ts
      '系统管理:/tenants,/telemetry-replay',
```

- [ ] **Step 6: Run the page test and the menu test**

Run: `cd conduit-ui && npm run test -- src/views/TelemetryReplayView.spec.ts src/layouts/menu.spec.ts`
Expected: both files pass — 6 tests in the view spec, 3 in the menu spec.

- [ ] **Step 7: Type-check the frontend**

Run: `cd conduit-ui && npm run typecheck`
Expected: no errors.

- [ ] **Step 8: Commit**

```bash
git add conduit-ui/src
git commit -m "feat(ui): add the telemetry replay page"
```

---

## Task 6: Documentation sync, full verification and acceptance

The last task is the only one that touches `CLAUDE.md` (its maintenance rule requires it), runs the whole build, and checks the feature against the stack the owner runs from the IDE.

**Files:**
- Modify: `README.md` (new 运维 section before 「## 已知限制」)
- Modify: `docs/durability-validation.md` (InfluxDB section)
- Modify: `CLAUDE.md` (§1.2 bullet, §4 decision line)
- Modify: `docs/superpowers/specs/2026-10-20-conduit-telemetry-replay-design.md` (status line only)
- Create: `docs/test-reports/2026-10-20-telemetry-replay.md`

**Interfaces:**
- Consumes: everything built in Tasks 1–5. No new code.
- Produces: no code. The acceptance record and the updated conventions.

**Decisions recorded here:**
- The spec's §7 asks for three documentation updates (README, durability validation, CLAUDE.md). The test report follows the repository's existing convention (`docs/test-reports/`), which the previous two features also used.
- Acceptance runs against the owner's locally run stack (`Conduit (local all)`), per CLAUDE.md §3. Nothing is deployed.

- [ ] **Step 1: Run the whole backend build**

Run: `mvn -B verify`
Expected: `BUILD SUCCESS`, every module's unit and integration tests green. If anything fails, fix it before continuing; do not edit documentation on top of a red build.

- [ ] **Step 2: Run the whole frontend suite and the type check**

Run: `cd conduit-ui && npm run test` then `npm run typecheck`
Expected: all spec files pass; no type errors.

- [ ] **Step 3: Run the e2e suite**

Run:

```bash
mvn -B -DskipTests install
E2E_DEV_HOST=<dev-host> mvn -B verify -Pe2e -pl conduit-e2e
```

Expected: all scenarios pass. The replay feature does not change the uplink path, so this is a regression check on the pipeline extraction of Task 1. It takes 6–10 minutes.

- [ ] **Step 4: Add the operations section to `README.md`**

Insert this section immediately before the `## 已知限制` heading:

```markdown
## 运维：InfluxDB 断电后恢复

InfluxDB 3 Core 的写入响应不代表数据已落盘（`object_store` 的本地文件实现没有 `sync_all`），所以非正常断电会丢掉内核回写窗口内（Linux 默认约 30 秒）的时序数据。这些数据并没有真正消失：上行报文仍留在 Redis 流 `conduit:stream:up` 中，直到被 `MAXLEN`（默认 100 万条）裁剪，按当前负载约可回溯 16 小时（随流量变化）。

恢复步骤：

1. 确认 InfluxDB 已正常启动（`/health`），再开始重放，否则重放会因写库失败而中止。
2. 以平台管理员登录界面，打开「系统管理 → 数据重放」。
3. 选择要恢复的时间范围：从断电前最后一次确认有数据的时刻，到服务恢复之后，单次最多 6 小时；跨度更大时分多次重放。结束时间不含在内。
4. 点击「开始重放」，页面每 2 秒刷新一次进度；需要时可以取消，已完成部分的统计保留。

重放是幂等的：同设备同时间戳的数据点会被覆盖为相同值，最新值按时间戳比较、旧值不会盖掉新值。重放不更新在线状态，也不重复写死信，因此不会影响首页的在线数与死信统计。不确定丢了哪一段时，直接重放最近一段即可，重复重放无害。

接口为 `POST` / `GET` / `DELETE` `/api/v1/platform/telemetry/replay`，仅平台管理员可用，同一时刻只允许一个任务（已有任务运行中再次提交返回 409）。任务状态保存在 server 进程内存中，server 重启后状态丢失（重放本身也已中断）。
```

- [ ] **Step 5: Record the investigation conclusion in `docs/durability-validation.md`**

Append these two sections at the end of the `## InfluxDB 恢复事件` section, immediately before the `## 复现入口` heading:

```markdown
### 写入落盘边界（2026-10-20 专项调查）

核对 v3.12.0 源码：`no_sync` 默认为 `false`（`influxdb3_types/src/http.rs`、`influxdb3_server/src/http.rs`），写入走 `WalObjectStore::write_ops`（`influxdb3_wal/src/object_store.rs`）等待 `object_store.put_opts` 返回；但 `object_store` 0.12.5 的 `LocalFileSystem::put_opts`（`src/local.rs`）只做 `write_all` 加 `rename`，**没有 `sync_all`**。因此 2xx 的含义是「字节已进入操作系统页缓存、文件已改名」，真正的丢数据窗口是内核回写窗口（Linux 默认约 30 秒），不是 WAL 的 1 秒刷新间隔。

实测：默认设置下强杀进程三轮均 2000/2000 幸存；`no_sync=true` 时 2000 条只剩 1380。因此默认值必须保持不变，不能为了吞吐改用 `no_sync=true`。

这个缺口无法在 Conduit 侧消除：缺的 fsync 在 `object_store` 内部。

### 恢复手段：遥测重放

数据并没有真正丢失。`XACK` 只把消息移出待确认列表，条目本身仍留在 Redis 上行流中直到被 `MAXLEN` 裁剪（默认 100 万条，按当前约每秒 17 条计约 16 小时），而 Redis 配置为 `appendfsync always`。所以恢复手段是把流中的原始报文重新灌一遍：平台管理员在「系统管理 → 数据重放」按时间范围重放（单次最多 6 小时），重放幂等、不更新在线状态、不重复写死信。步骤见 README「运维：InfluxDB 断电后恢复」。
```

- [ ] **Step 6: Sync `CLAUDE.md` (§1.2 and §4)**

In §1.2「关键设计决定」, add this bullet immediately after the 「**历史查询**（V0.2-B）」bullet:

```markdown
- **遥测重放**（V0.2-R3）：平台管理员按时间范围把 Redis 上行流中保留的原始报文重新写入时序库，用于 InfluxDB 断电丢数后的恢复。接口 `POST`/`GET`/`DELETE` `/api/v1/platform/telemetry/replay`（权限沿用 `/api/v1/platform/**` 规则），体 `{from, to}` 为毫秒、左闭右开，二者在 0～253402300799999 内、`from < to`、跨度不超过 6 小时，否则 400；同一时刻只允许一个任务（运行中再次提交 409，查询与取消都不带任务 id），`GET` 在从未提交时返回 `{"state":"NONE"}`。任务状态只在内存（单个 `AtomicReference`，不持久化，最近一次完成的状态保留到下次提交）。执行：按条目 id 区间 `XRANGE` 分批（每批 500）读取（流条目 id 前半段即毫秒时间戳，区间映射为 `[from-0, (to-1)-最大序号]`），每批之间检查取消标志；可恢复错误退避重试至多 5 次，超限则 `FAILED`。统计 `scanned`/`written`/`skipped`/`rejected`。**重放不更新在线状态、不写死信**（旧数据会把离线设备标成在线、死信会被重复写入污染首页统计），因为写入幂等（同 ts 覆盖、最新值按 ts 比较），可与正常消费并行、重复重放无害。实现上「解码 → 校验 → 写库 → 写最新值」抽为 `TelemetryIngestor`，退避重试抽为 `Backoff`（流水线用无上限、重放用有上限），由正常消费与重放共用；`MessageBus` 增加只读的 `range(startId, endId, count)`（不进消费组、不改待确认列表）。前端「系统管理 → 数据重放」页提交范围、每 2 秒轮询、可取消。
```

In §4「决定记录」, append this line at the end of the list:

```markdown
- 2026-10-20 V0.2-R3：遥测重放——`/api/v1/platform/telemetry/replay` 三个接口，范围左闭右开、跨度上限 6 小时、每批 500、同时只允许一个任务且状态只在内存；重放不更新在线状态、不写死信；`PipelineProcessor` 抽出 `TelemetryIngestor` 与 `Backoff` 供正常消费与重放共用，`MessageBus` 增加 `range` 按条目 id 区间只读（`2026-10-20-conduit-telemetry-replay-design.md`）。
```

- [ ] **Step 7: Mark the spec as implemented**

In `docs/superpowers/specs/2026-10-20-conduit-telemetry-replay-design.md`, change the status line

```markdown
- 状态：已评审，待实现
```

to

```markdown
- 状态：已实现
```

Change nothing else in the spec.

- [ ] **Step 8: Acceptance against the locally run stack**

The owner runs `Conduit (local all)` from the IDE (server, transport, simulator, UI) with the dev infrastructure up on the Docker host. Then, in a shell:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"sysadmin\",\"password\":\"$SYSADMIN_PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# 1. 从未提交过任务（server 刚启动时）
curl -s http://localhost:8080/api/v1/platform/telemetry/replay -H "Authorization: Bearer $TOKEN"

# 2. 参数校验：跨度超过 6 小时 → 400
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/v1/platform/telemetry/replay \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"from":0,"to":21600001}'

# 3. 重放最近 10 分钟（模拟器正在上报，流里一定有数据）
NOW=$(($(date +%s) * 1000)); FROM=$((NOW - 600000))
curl -s -X POST http://localhost:8080/api/v1/platform/telemetry/replay \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"from\":$FROM,\"to\":$NOW}"

# 4. 轮询直到结束
curl -s http://localhost:8080/api/v1/platform/telemetry/replay -H "Authorization: Bearer $TOKEN"

# 5. 没有运行中的任务时取消 → 409
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://localhost:8080/api/v1/platform/telemetry/replay \
  -H "Authorization: Bearer $TOKEN"
```

Expected: (1) `{"state":"NONE"}`; (2) `400`; (3) a `RUNNING` status with the submitted range; (4) `SUCCEEDED` with `scanned` > 0 and `written` > 0; (5) `409`.

Then in the browser, as `sysadmin`:

- 「系统管理」菜单里有「数据重放」, and the page loads with an `el-empty` state after a fresh server start.
- Picking a range longer than 6 hours shows「时间跨度不能超过 6 小时」without any request.
- Submitting a valid range shows the confirmation, then the task card with state tag, times, the four counters and the elapsed time; the counters advance while it runs.
- Cancelling a running task sets「已取消」and keeps the statistics.
- Log in as a tenant admin (default tenant `admin`): the「数据重放」menu entry is absent, and opening `/telemetry-replay` directly lands on the tenant home instead.
- Check `logs/conduit-server.log`: one INFO line per submission, cancellation and completion; no secrets.

Also confirm the two spec invariants on the running stack:

- Pick a device that is offline on the device list, replay a window that contains its old data, and confirm it is still offline afterwards.
- Note the「最近 1 小时死信」count on the home page before and after a replay — it must not change.

- [ ] **Step 9: Write the test report**

Create `docs/test-reports/2026-10-20-telemetry-replay.md` with the real results of Steps 1–3 and 8: date, commit, the exact commands run, the pass/fail of `mvn -B verify`, the frontend suite, the e2e run, and each acceptance check above with its observed value (counters, status codes, whether the offline device stayed offline, whether the dead-letter count changed). Record failures and what was done about them. Write it in Chinese, matching `docs/test-reports/2026-10-20-demo-seed.md`. Do not put host names, IP addresses, local paths or passwords into it.

- [ ] **Step 10: Commit**

```bash
git add README.md docs CLAUDE.md
git commit -m "docs: record the telemetry replay feature and its verification"
```

---

## Self-Review Notes

**1. Spec coverage.** Every section of `2026-10-20-conduit-telemetry-replay-design.md` maps to a task:

| Spec | Where |
|---|---|
| §4.1 three endpoints, paths, bodies | Task 4 Step 3 (`ReplayController`) |
| §4.1 validation (from < to, 0–253402300799999, 6 h) → 400 | Task 3 Steps 1–3 (`ReplayRequest`), Task 4 Step 1 (through HTTP) |
| §4.1 only one task, 409 | Task 3 (`ReplayService.start`), Task 4 (`DELETE` 409 path) |
| §4.1 platform-admin-only, no new rule | Task 4 Step 1 `tenantRolesCannotReachAnyOfTheThreeEndpoints` |
| §4.2 in-memory state, all status fields, `{"state":"NONE"}` | Task 3 (`ReplayTask`, `ReplayStatus`) |
| §4.3 item 1 `XRANGE` by id range, batches of 500, half-open | Task 2 (`MessageBus.range`), Task 3 (`run`, `nextId`, `BATCH_SIZE`) |
| §4.3 item 2 reuse of the pipeline path | Task 1 (`TelemetryIngestor`), Task 3 (`replayBatch`) |
| §4.3 item 3 skip presence | Task 3 (no `PresenceStore` collaborator), Task 4 `doesNotBringAnOfflineDeviceBackOnline` |
| §4.3 item 4 skip dead letters, count only | Task 3 `neverWritesDeadLettersAndNeverAcks`, Task 4 `countsFullyRejectedMessagesWithoutWritingDeadLetters` |
| §4.3 item 5 cancel between batches, keep statistics | Task 3 `cancellingStopsTheTaskAndKeepsTheStatisticsSoFar` |
| §4.3 item 6 backoff, FAILED past the limit | Task 1 (`Backoff`), Task 3 `failsWithAReasonWhenTheWriteKeepsFailing` |
| §4.3 last paragraph: extract the shared unit | Task 1 |
| §5 page, `ROUTE_META`, picker, confirmation, task card, 2 s polling, empty state | Task 5 |
| §6.1 server tests (validation, 409, permissions, integration, presence, dead letters, cancel) | Tasks 3 and 4 |
| §6.2 frontend tests (span, polling, timer cleanup, cancel button, empty state) | Task 5 Step 1 |
| §7 docs (README, durability-validation, CLAUDE.md) | Task 6 Steps 4–6 |
| §8 acceptance criteria 1–4 | Task 6 Steps 1–3 and 8 |

One spec detail deliberately re-scoped: §6.1 asks the integration test to「清空 InfluxDB 对应时间段」before replaying. InfluxDB 3 Core has no delete-by-range API in the client this project wrote, so `ReplayIT` instead publishes entries that the pipeline never consumed (the test profile disables it) and asserts they arrive in InfluxDB through replay alone. That proves the same thing — replay alone recovers the points — without needing a delete.

**2. Placeholder scan.** No "TBD", no "add error handling", no "similar to Task N", no step that names a test without its code. Every type referenced in a later task is defined in an earlier one: `TelemetryIngestor.Prepared`, `Backoff.run`, `ProcessingAbortedException`, `MessageBus.range`, `ReplayRequest.validated`, `ReplayStatus.none`, `ReplayState`, `ReplayTask`, `ReplayService.BATCH_SIZE`, `replayApi`, `ROUTE_META['telemetry-replay']`.

**3. Type consistency.** Checked across tasks: `prepare(String traceId, RawMessage)` is called with the delivery id in `PipelineProcessor` and the stream entry id in `ReplayService`; `write(List<TelemetryPoint>)` returns `WriteResult` whose `rejected()` carries `index()`, used identically in both callers; `Backoff.run` is used with a value-returning lambda everywhere and `return null` for void actions; `ReplayStatus` field names match the frontend `ReplayStatus` interface one for one (`state`, `from`, `to`, `startedAt`, `finishedAt`, `scanned`, `written`, `skipped`, `rejected`, `error`); the route name `telemetry-replay` is identical in `ROUTE_META`, `router/index.ts`, `viewTestUtils.ts` and the menu path `/telemetry-replay`.
