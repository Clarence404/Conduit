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
