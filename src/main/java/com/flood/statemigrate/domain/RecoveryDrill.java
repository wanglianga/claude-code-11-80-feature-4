package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 可恢复性演练：在隔离环境（isolatedEnv）从指定检查点恢复，
 * 用检查点的规则包重放输入位点区间得到期望输出摘要，与从快照恢复出的输出摘要比对。
 * 摘要不一致时该快照及其派生压缩包被冻结。
 */
@Entity
@Table(name = "recovery_drill")
public class RecoveryDrill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private Long checkpointId;

    @Column(nullable = false)
    private int partitionNo;

    /** 隔离演练环境标识 */
    @Column(nullable = false, length = 128)
    private String isolatedEnv;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrillStatus status;

    @Column(length = 128)
    private String expectedDigest;

    @Column(length = 128)
    private String actualDigest;

    @Column(nullable = false)
    private boolean digestMatch;

    @Column(nullable = false)
    private long inputOffsetFrom;

    @Column(nullable = false)
    private long inputOffsetTo;

    /** 恢复来源：STATE_SNAPSHOT / ARCHIVE:<packageRef> */
    @Column(length = 128)
    private String restoreSource;

    @Column(columnDefinition = "TEXT")
    private String reportJson;

    @Column(nullable = false, updatable = false)
    private Instant runAt;

    @PrePersist
    void onCreate() { runAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public Long getCheckpointId() { return checkpointId; }
    public void setCheckpointId(Long checkpointId) { this.checkpointId = checkpointId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public String getIsolatedEnv() { return isolatedEnv; }
    public void setIsolatedEnv(String isolatedEnv) { this.isolatedEnv = isolatedEnv; }
    public DrillStatus getStatus() { return status; }
    public void setStatus(DrillStatus status) { this.status = status; }
    public String getExpectedDigest() { return expectedDigest; }
    public void setExpectedDigest(String expectedDigest) { this.expectedDigest = expectedDigest; }
    public String getActualDigest() { return actualDigest; }
    public void setActualDigest(String actualDigest) { this.actualDigest = actualDigest; }
    public boolean isDigestMatch() { return digestMatch; }
    public void setDigestMatch(boolean digestMatch) { this.digestMatch = digestMatch; }
    public long getInputOffsetFrom() { return inputOffsetFrom; }
    public void setInputOffsetFrom(long v) { this.inputOffsetFrom = v; }
    public long getInputOffsetTo() { return inputOffsetTo; }
    public void setInputOffsetTo(long v) { this.inputOffsetTo = v; }
    public String getRestoreSource() { return restoreSource; }
    public void setRestoreSource(String restoreSource) { this.restoreSource = restoreSource; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public Instant getRunAt() { return runAt; }
}
