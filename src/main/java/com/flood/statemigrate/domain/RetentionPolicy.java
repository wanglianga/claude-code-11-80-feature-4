package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 检查点保留策略：按预警等级定义基线保留期与要求演练次数，
 * 并定义跨越规则变更、产出高影响输出时叠加的延长天数。
 */
@Entity
@Table(name = "retention_policy",
        uniqueConstraints = @UniqueConstraint(columnNames = {"streamId", "warningLevel"}))
public class RetentionPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WarningLevel warningLevel;

    /** 基线保留天数 */
    @Column(nullable = false)
    private int retentionDays;

    /** 压缩前必须通过的可恢复性演练次数 */
    @Column(nullable = false)
    private int requiredDrills;

    /** 检查点跨规则包版本变更边界时叠加的保留天数 */
    @Column(nullable = false)
    private int ruleChangeExtraDays;

    /** 产出高影响输出（HIGH）时叠加的保留天数 */
    @Column(nullable = false)
    private int highImpactExtraDays;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public WarningLevel getWarningLevel() { return warningLevel; }
    public void setWarningLevel(WarningLevel warningLevel) { this.warningLevel = warningLevel; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public int getRequiredDrills() { return requiredDrills; }
    public void setRequiredDrills(int requiredDrills) { this.requiredDrills = requiredDrills; }
    public int getRuleChangeExtraDays() { return ruleChangeExtraDays; }
    public void setRuleChangeExtraDays(int v) { this.ruleChangeExtraDays = v; }
    public int getHighImpactExtraDays() { return highImpactExtraDays; }
    public void setHighImpactExtraDays(int v) { this.highImpactExtraDays = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
