package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 快照谱系索引：把检查点快照与状态模式、输入位点、当时生效的规则包版本、
 * 临时绕过规则以及相邻快照关联起来。扩缩容事件不写入本表，
 * 查询时按 occurredAt 与快照创建时间的相对关系计算恢复所需的分区映射。
 *
 * bypassRulesJson 示例：
 * [{"ruleKey":"CREDIBILITY_FLOOR","partitionNo":2,"effectiveFrom":"...","reason":"暴雨期传感器校准"}]
 */
@Entity
@Table(name = "snapshot_lineage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"checkpointId"}))
public class SnapshotLineage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private Long checkpointId;

    /** 快照产生时的物理分区号（历史拓扑） */
    @Column(nullable = false)
    private int partitionNo;

    @Column(length = 64)
    private String roadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StateSchema stateSchema;

    @Column(nullable = false, length = 32)
    private String rulePackageVersion;

    private Instant windowStart;

    private Instant windowEnd;

    /** 输入位点区间（该快照消费到的事件位点） */
    @Column(nullable = false)
    private long inputOffsetFrom;

    @Column(nullable = false)
    private long inputOffsetTo;

    /** 同一分区上一条相邻快照 */
    private Long previousCheckpointId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String bypassRulesJson;

    /** 快照对应的汛情预警等级（保留期基线） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WarningLevel warningLevel;

    /** 演练摘要不一致后冻结，禁止压缩与派生归档 */
    @Column(nullable = false)
    private boolean frozen;

    @Column(length = 512)
    private String frozenReason;

    /** 快照被压缩归档后指向的包引用；原文仍保留供演练，可清理 */
    @Column(length = 96)
    private String archivedPackageRef;

    /** 是否具备完整谱系（模式/位点/窗口/规则/快照或归档均可解析） */
    @Column(nullable = false)
    private boolean lineageComplete;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public Long getCheckpointId() { return checkpointId; }
    public void setCheckpointId(Long checkpointId) { this.checkpointId = checkpointId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }
    public StateSchema getStateSchema() { return stateSchema; }
    public void setStateSchema(StateSchema stateSchema) { this.stateSchema = stateSchema; }
    public String getRulePackageVersion() { return rulePackageVersion; }
    public void setRulePackageVersion(String v) { this.rulePackageVersion = v; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public long getInputOffsetFrom() { return inputOffsetFrom; }
    public void setInputOffsetFrom(long v) { this.inputOffsetFrom = v; }
    public long getInputOffsetTo() { return inputOffsetTo; }
    public void setInputOffsetTo(long v) { this.inputOffsetTo = v; }
    public Long getPreviousCheckpointId() { return previousCheckpointId; }
    public void setPreviousCheckpointId(Long v) { this.previousCheckpointId = v; }
    public String getBypassRulesJson() { return bypassRulesJson; }
    public void setBypassRulesJson(String v) { this.bypassRulesJson = v; }
    public WarningLevel getWarningLevel() { return warningLevel; }
    public void setWarningLevel(WarningLevel warningLevel) { this.warningLevel = warningLevel; }
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public String getFrozenReason() { return frozenReason; }
    public void setFrozenReason(String frozenReason) { this.frozenReason = frozenReason; }
    public String getArchivedPackageRef() { return archivedPackageRef; }
    public void setArchivedPackageRef(String archivedPackageRef) { this.archivedPackageRef = archivedPackageRef; }
    public boolean isLineageComplete() { return lineageComplete; }
    public void setLineageComplete(boolean lineageComplete) { this.lineageComplete = lineageComplete; }
    public Instant getCreatedAt() { return createdAt; }
}
