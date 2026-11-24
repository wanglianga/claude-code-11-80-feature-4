package com.flood.statemigrate.domain;

/**
 * 汛情预警等级：蓝 < 黄 < 橙 < 红。
 * 检查点保留期以预警等级为基线，规则变更与高影响输出再叠加延长天数。
 */
public enum WarningLevel {
    BLUE(7, 1),
    YELLOW(30, 1),
    ORANGE(90, 2),
    RED(180, 2);

    private final int defaultRetentionDays;
    private final int defaultRequiredDrills;

    WarningLevel(int defaultRetentionDays, int defaultRequiredDrills) {
        this.defaultRetentionDays = defaultRetentionDays;
        this.defaultRequiredDrills = defaultRequiredDrills;
    }

    public int defaultRetentionDays() {
        return defaultRetentionDays;
    }

    public int defaultRequiredDrills() {
        return defaultRequiredDrills;
    }

    /** 由已记录的风险等级推导预警等级（用于快照未显式登记预警等级时） */
    public static WarningLevel fromRisk(RiskLevel risk) {
        if (risk == null) return BLUE;
        return switch (risk) {
            case HIGH -> RED;
            case MEDIUM -> ORANGE;
            case LOW -> YELLOW;
        };
    }
}
