package dev.conduit.server.web;

import java.util.List;

/**
 * 分页列表的统一响应体。
 *
 * @param items 当前页数据
 * @param total 符合条件的总条数
 * @param page  页码（从 1 开始）
 * @param size  每页条数
 */
public record PageView<T>(List<T> items, long total, long page, long size) {
}
