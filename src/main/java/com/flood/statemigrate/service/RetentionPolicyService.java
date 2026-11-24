package com.flood.statemigrate.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 检查点保留策略器：按预警等级给出基线保留期，并叠加
 * 规则包版本变更、高影响输出（HIGH）造成的延长。
 * 保留期是"可压缩/可清理窗口"的准入，是否压缩仍以可恢复性证明为准。
 */
@Service
public class RetentionPolicyService {

    private static final int DEFAULT_RULE_CHANGE_EXTRA = 30;
    private static final int DEFAULT_HIGH_IMPACT_EXTRA = 30;

    private final RetentionPolicyRepo policyRepo;
    private final SnapshotLineageRepo lineageRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final StreamService streamService;

    public RetentionPolicyService(RetentionPolicyRepo policyRepo, SnapshotLineageRepo lineageRepo,
                                  EvidenceEntryRepo evidenceRepo, StreamService streamService) {
        this.policyRepo = policyRepo;
        this.lineageRepo = lineageRepo;
        this.evidenceRepo = evidenceRepo;
        this.streamService = streamService;
    }

    /** 登记/更新预警等级的保留策略（同流同等级唯一） */
    @Transactional
    public RetentionPolicy upsert(Long streamId, RetentionPolicy policy) {
        streamService.get(streamId);
        RetentionPolicy target = policyRepo
                .findByStreamIdAndWarningLevel(streamId, policy.getWarningLevel())
                .orElseGet(RetentionPolicy::new);
        target.setStreamId(streamId);
        target.setWarningLevel(policy.getWarningLevel());
        target.setRetentionDays(policy.getRetentionDays());
        target.setRequiredDrills(policy.getRequiredDrills());
        target.setRuleChangeExtraDays(policy.getRuleChangeExtraDays());
        target.setHighImpactExtraDays(policy.getHighImpactExtraDays());
        return policyRepo.save(target);
    }

    public List<RetentionPolicy> list(Long streamId) {
        streamService.get(streamId);
        return policyRepo.findByStreamIdOrderByWarningLevelAsc(streamId);
    }

    /** 某预警等级生效的保留策略（未配置时使用等级默认值） */
    public Effective effectivePolicy(Long streamId, WarningLevel level) {
        return policyRepo.findByStreamIdAndWarningLevel(streamId, level)
                .map(p -> new Effective(level, p.getRetentionDays(), p.getRequiredDrills(),
                        p.getRuleChangeExtraDays(), p.getHighImpactExtraDays(), false))
                .orElseGet(() -> new Effective(level, level.defaultRetentionDays(),
                        level.defaultRequiredDrills(), DEFAULT_RULE_CHANGE_EXTRA,
                        DEFAULT_HIGH_IMPACT_EXTRA, true));
    }

    /** 逐快照计算保留期：基线 + 规则变更延长 + 高影响输出延长 */
    public Map<String, Object> evaluate(Long streamId) {
        StreamDefinition stream = streamService.get(streamId);
        Instant now = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        int expired = 0;
        int withinRetention = 0;
        for (SnapshotLineage l : lineageRepo.findByStreamIdOrderByWindowEndAscIdAsc(streamId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            WarningLevel level = l.getWarningLevel() != null ? l.getWarningLevel() : WarningLevel.YELLOW;
            Effective policy = effectivePolicy(streamId, level);
            int days = policy.retentionDays();
            List<String> extensions = new ArrayList<>();
            List<String> triggers = new ArrayList<>();

            if (crossesRuleChange(streamId, stream, l)) {
                days += policy.ruleChangeExtraDays();
                triggers.add("RULE_CHANGE");
                extensions.add("检查点跨规则包版本边界 +" + policy.ruleChangeExtraDays() + "d");
            }
            if (highImpact(streamId, l)) {
                days += policy.highImpactExtraDays();
                triggers.add("HIGH_IMPACT_OUTPUT");
                extensions.add("产出高影响输出（HIGH）+" + policy.highImpactExtraDays() + "d");
            }

            Instant base = l.getWindowEnd() != null ? l.getWindowEnd()
                    : (l.getCreatedAt() != null ? l.getCreatedAt() : now);
            Instant expiresAt = base.plus(Duration.ofDays(days));
            boolean isExpired = expiresAt.isBefore(now);
            boolean tripwire = days > policy.retentionDays();

            row.put("checkpointId", l.getCheckpointId());
            row.put("partitionNo", l.getPartitionNo());
            row.put("warningLevel", level);
            row.put("baseDays", policy.retentionDays());
            row.put("effectiveDays", days);
            row.put("extensionTriggers", triggers);
            row.put("policySource", policy.defaulted() ? "DEFAULT" : "CONFIGURED");
            row.put("retentionUntil", expiresAt);
            row.put("expired", isExpired);
            row.put("frozen", l.isFrozen());
            row.put("requiresDrills", policy.requiredDrills());
            row.put("extensions", extensions);
            row.put("note", isExpired
                    ? "已超出可清理窗口；清理前仍须演练通过并完成谱系归档"
                    : (tripwire ? "因规则变更/高影响输出延长了保留期" : "处于基线保留期内"));
            rows.add(row);
            if (isExpired) expired++; else withinRetention++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("streamId", streamId);
        result.put("streamCode", stream.getStreamCode());
        result.put("totalSnapshots", rows.size());
        result.put("withinRetention", withinRetention);
        result.put("expired", expired);
        result.put("policies", list(streamId));
        result.put("snapshots", rows);
        return result;
    }

    /** 快照所在的规则包版本是否与同分区相邻快照不同（跨版本边界） */
    private boolean crossesRuleChange(Long streamId, StreamDefinition stream, SnapshotLineage l) {
        List<SnapshotLineage> samePartition = lineageRepo
                .findByStreamIdAndPartitionNoOrderByInputOffsetToAsc(streamId, l.getPartitionNo());
        int idx = -1;
        for (int i = 0; i < samePartition.size(); i++) {
            if (samePartition.get(i).getCheckpointId().equals(l.getCheckpointId())) {
                idx = i;
                break;
            }
        }
        String version = l.getRulePackageVersion();
        if (idx > 0 && !Objects.equals(samePartition.get(idx - 1).getRulePackageVersion(), version)) {
            return true;
        }
        return idx >= 0 && idx < samePartition.size() - 1
                && !Objects.equals(samePartition.get(idx + 1).getRulePackageVersion(), version);
    }

    private boolean highImpact(Long streamId, SnapshotLineage l) {
        // 证据账中该快照对应时间段有 HIGH 风险输出
        for (EvidenceEntry e : evidenceRepo.findByStreamIdAndPartitionNoOrderByIdAsc(streamId, l.getPartitionNo())) {
            if (e.getRiskLevel() != RiskLevel.HIGH) continue;
            if (e.getWatermarkOffset() == null) continue;
            if (e.getWatermarkOffset() >= l.getInputOffsetFrom()
                    && e.getWatermarkOffset() <= l.getInputOffsetTo()) {
                return true;
            }
        }
        // 快照自身已标注高风险
        return l.getWarningLevel() == WarningLevel.RED;
    }

    public record Effective(WarningLevel level, int retentionDays, int requiredDrills,
                            int ruleChangeExtraDays, int highImpactExtraDays, boolean defaulted) {
    }
}
