package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 分区扩缩容事件：记录发生在检查点之间的拓扑变化。
 *
 * mappingJson 示例（4→5，分区 3 拆分出 4）：
 * [{"from":0,"to":0},{"from":1,"to":1},{"from":2,"to":2},{"from":3,"to":4},
 *  {"from":3,"to":3,"split":true}]
 * 恢复旧快照时必须按映射重写分区号，禁止假设旧分区号可直接恢复。
 */
@Entity
@Table(name = "partition_scale_event")
public class PartitionScaleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int fromPartitions;

    @Column(nullable = false)
    private int toPartitions;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String mappingJson;

    /** 拓扑变化生效时刻，用于判断某检查点之后是否发生扩缩容 */
    @Column(nullable = false)
    private Instant occurredAt;

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
    public int getFromPartitions() { return fromPartitions; }
    public void setFromPartitions(int fromPartitions) { this.fromPartitions = fromPartitions; }
    public int getToPartitions() { return toPartitions; }
    public void setToPartitions(int toPartitions) { this.toPartitions = toPartitions; }
    public String getMappingJson() { return mappingJson; }
    public void setMappingJson(String mappingJson) { this.mappingJson = mappingJson; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
}
