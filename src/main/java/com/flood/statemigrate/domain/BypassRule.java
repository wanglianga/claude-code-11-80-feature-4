package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 临时绕过规则：汛期应急期间对某项校验/阈值的临时豁免。
 * partitionNo 为空表示全流生效；谱系索引按快照创建时刻记录当时生效的绕过规则。
 */
@Entity
@Table(name = "bypass_rule")
public class BypassRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    /** 被绕过的规则键，如 CREDIBILITY_FLOOR / SESSION_TIMEOUT_CHECK */
    @Column(nullable = false, length = 64)
    private String ruleKey;

    /** 空=全流生效 */
    private Integer partitionNo;

    @Column(nullable = false)
    private Instant effectiveFrom;

    private Instant effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BypassRuleStatus status;

    @Column(length = 256)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }
    public Integer getPartitionNo() { return partitionNo; }
    public void setPartitionNo(Integer partitionNo) { this.partitionNo = partitionNo; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Instant effectiveTo) { this.effectiveTo = effectiveTo; }
    public BypassRuleStatus getStatus() { return status; }
    public void setStatus(BypassRuleStatus status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }

    /** 在指定时刻是否生效 */
    public boolean activeAt(Instant moment) {
        if (moment == null) return false;
        if (effectiveFrom != null && moment.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || !moment.isAfter(effectiveTo);
    }
}
