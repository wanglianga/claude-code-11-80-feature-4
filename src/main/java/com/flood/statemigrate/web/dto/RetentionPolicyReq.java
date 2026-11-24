package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.WarningLevel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 保留策略登记/更新 */
public record RetentionPolicyReq(
        @NotNull WarningLevel warningLevel,
        @Min(1) int retentionDays,
        @Min(0) int requiredDrills,
        @Min(0) int ruleChangeExtraDays,
        @Min(0) int highImpactExtraDays
) {
}
