package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** 事故复原请求：调查时间窗 + 关注分区（为空则全部分区） */
public record IncidentReq(
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        List<Integer> partitions
) {
}
