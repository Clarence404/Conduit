package dev.conduit.server.web;

import java.util.List;

public record PageView<T>(List<T> items, long total, long page, long size) {
}
