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
