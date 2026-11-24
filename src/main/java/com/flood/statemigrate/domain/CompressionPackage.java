package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 压缩归档包：仅收录已通过可恢复性演练且谱系完整的快照。
 * payloadsJson 为 {checkpointId: base64(gzip(快照原文))}，归档后检查点原文置空。
 * 任一成员快照演练摘要不一致时，本包及其派生包被冻结（status=FROZEN）。
 */
@Entity
@Table(name = "compression_package")
public class CompressionPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false, length = 96)
    private String packageRef;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String checkpointIdsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String schemasJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CompressionStatus status;

    @Column(nullable = false)
    private boolean frozen;

    @Column(length = 512)
    private String freezeReason;

    /** 派生自哪个压缩包（为空表示直接压缩检查点） */
    @Column(length = 96)
    private String derivedFromPackageRef;

    private long originalBytes;

    private long compressedBytes;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadsJson;

    @Column(length = 256)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public String getPackageRef() { return packageRef; }
    public void setPackageRef(String packageRef) { this.packageRef = packageRef; }
    public String getCheckpointIdsJson() { return checkpointIdsJson; }
    public void setCheckpointIdsJson(String v) { this.checkpointIdsJson = v; }
    public String getSchemasJson() { return schemasJson; }
    public void setSchemasJson(String schemasJson) { this.schemasJson = schemasJson; }
    public CompressionStatus getStatus() { return status; }
    public void setStatus(CompressionStatus status) { this.status = status; }
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public String getFreezeReason() { return freezeReason; }
    public void setFreezeReason(String freezeReason) { this.freezeReason = freezeReason; }
    public String getDerivedFromPackageRef() { return derivedFromPackageRef; }
    public void setDerivedFromPackageRef(String v) { this.derivedFromPackageRef = v; }
    public long getOriginalBytes() { return originalBytes; }
    public void setOriginalBytes(long originalBytes) { this.originalBytes = originalBytes; }
    public long getCompressedBytes() { return compressedBytes; }
    public void setCompressedBytes(long compressedBytes) { this.compressedBytes = compressedBytes; }
    public String getPayloadsJson() { return payloadsJson; }
    public void setPayloadsJson(String payloadsJson) { this.payloadsJson = payloadsJson; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
}
