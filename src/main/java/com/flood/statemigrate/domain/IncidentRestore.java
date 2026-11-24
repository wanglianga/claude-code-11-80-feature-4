package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 事故复原工作台记录：按事故调查时间窗组合相邻快照，给出最小必要分区、
 * 分段恢复计划与不可还原空档；跨状态模式时显式标注。
 */
@Entity
@Table(name = "incident_restore")
public class IncidentRestore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String requestedPartitionsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String minimalPartitionsJson;

    /** 时间窗是否跨越两种状态模式 */
    @Column(nullable = false)
    private boolean schemaTransition;

    /** READY / PARTIAL / NO_COVERAGE */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String resultJson;

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
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public String getRequestedPartitionsJson() { return requestedPartitionsJson; }
    public void setRequestedPartitionsJson(String v) { this.requestedPartitionsJson = v; }
    public String getMinimalPartitionsJson() { return minimalPartitionsJson; }
    public void setMinimalPartitionsJson(String v) { this.minimalPartitionsJson = v; }
    public boolean isSchemaTransition() { return schemaTransition; }
    public void setSchemaTransition(boolean schemaTransition) { this.schemaTransition = schemaTransition; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
}
