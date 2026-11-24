package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 压缩资格判定器：以可恢复性证明驱动压缩，而不是按日期或保留期机械判断。
 * 仅当快照满足全部条件时才允许压缩：
 *  1) 谱系完整且未冻结；
 *  2) 最近一次隔离演练通过（输出摘要一致）；
 *  3) 通过演练次数达到保留策略要求；
 *  4) 快照仍可恢复（原文或可解压归档）。
 */
@Service
public class CompressionEligibilityService {

    private final SnapshotLineageRepo lineageRepo;
    private final RecoveryDrillRepo drillRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final CompressionPackageRepo packageRepo;
    private final RetentionPolicyRepo policyRepo;
    private final CompressionFreezeService freezeService;
    private final StreamService streamService;

    public CompressionEligibilityService(SnapshotLineageRepo lineageRepo, RecoveryDrillRepo drillRepo,
                                         CheckpointManifestRepo checkpointRepo,
                                         CompressionPackageRepo packageRepo,
                                         RetentionPolicyRepo policyRepo,
                                         CompressionFreezeService freezeService,
                                         StreamService streamService) {
        this.lineageRepo = lineageRepo;
        this.drillRepo = drillRepo;
        this.checkpointRepo = checkpointRepo;
        this.packageRepo = packageRepo;
        this.policyRepo = policyRepo;
        this.freezeService = freezeService;
        this.streamService = streamService;
    }

    /** 单快照压缩资格 */
    public Map<String, Object> evaluate(Long streamId, Long checkpointId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkpointId", checkpointId);
        List<String> reasons = new ArrayList<>();

        CheckpointManifest cp = checkpointRepo.findById(checkpointId).orElse(null);
        if (cp == null || !cp.getStreamId().equals(streamId)) {
            result.put("eligible", false);
            result.put("reason", "检查点不存在或不属于该流");
            return result;
        }
        SnapshotLineage lineage = lineageRepo.findByCheckpointId(checkpointId).orElse(null);
        if (lineage == null) {
            result.put("eligible", false);
            result.put("reason", "无谱系索引，无法证明快照来源与模式边界");
            return result;
        }

        WarningLevel level = lineage.getWarningLevel() != null
                ? lineage.getWarningLevel() : WarningLevel.YELLOW;
        int requiredDrills = policyRepo.findByStreamIdAndWarningLevel(streamId, level)
                .map(RetentionPolicy::getRequiredDrills)
                .orElse(level.defaultRequiredDrills());
        long passed = drillRepo.countByStreamIdAndCheckpointIdAndStatusAndDigestMatchTrue(
                streamId, checkpointId, DrillStatus.PASSED);
        RecoveryDrill latest = drillRepo.findByStreamIdAndCheckpointIdOrderByIdDesc(streamId, checkpointId)
                .stream().findFirst().orElse(null);

        boolean lineageComplete = lineage.isLineageComplete() && !lineage.isFrozen();
        boolean latestOk = latest != null && latest.getStatus() == DrillStatus.PASSED && latest.isDigestMatch();
        boolean drillCountOk = passed >= requiredDrills;
        boolean recoverable = cp.getStateSnapshotJson() != null || lineage.getArchivedPackageRef() != null;
        boolean frozenPackage = lineage.getArchivedPackageRef() != null
                && packageRepo.findByPackageRef(lineage.getArchivedPackageRef())
                        .map(p -> p.isFrozen() || freezeService.taintedByFrozenAncestor(p))
                        .orElse(false);

        if (!lineageComplete) {
            reasons.add(lineage.isFrozen()
                    ? "谱系已冻结：" + lineage.getFrozenReason()
                    : "谱系不完整（缺少模式/位点/窗口/规则包或快照原文）");
        }
        if (latest == null) {
            reasons.add("尚未在隔离环境演练，无法建立可恢复性证明");
        } else if (latest.getStatus() == DrillStatus.FAILED) {
            reasons.add("最近一次演练输出摘要不一致（drill-" + latest.getId() + "），快照被冻结");
        } else if (latest.getStatus() == DrillStatus.BLOCKED) {
            reasons.add("最近一次演练无法完成（drill-" + latest.getId() + "）");
        }
        if (!latestOk && latest != null && latest.getStatus() == DrillStatus.PASSED) {
            reasons.add("最近一次演练未通过");
        }
        if (!drillCountOk) {
            reasons.add("通过演练次数 " + passed + " 未达预警等级 " + level + " 要求的 " + requiredDrills + " 次");
        }
        if (!recoverable) {
            reasons.add("快照既无原文也无可用归档，无法再次验证");
        }
        if (frozenPackage) {
            reasons.add("快照所属归档包已冻结（或派生自冻结包），禁止继续压缩或派生");
        }

        result.put("warningLevel", level);
        result.put("requiredDrills", requiredDrills);
        result.put("passedDrills", passed);
        result.put("latestDrillStatus", latest == null ? null : latest.getStatus());
        result.put("lineageComplete", lineage.isLineageComplete());
        result.put("frozen", lineage.isFrozen());
        result.put("eligible", reasons.isEmpty());
        result.put("reason", reasons.isEmpty()
                ? "演练通过且谱系完整，允许压缩归档" : String.join("；", reasons));
        return result;
    }

    /** 流内全部快照的压缩资格清单 */
    public List<Map<String, Object>> evaluateStream(Long streamId) {
        streamService.get(streamId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SnapshotLineage l : lineageRepo.findByStreamIdOrderByWindowEndAscIdAsc(streamId)) {
            rows.add(evaluate(streamId, l.getCheckpointId()));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> assertEligible(Long streamId, Long checkpointId) {
        Map<String, Object> verdict = evaluate(streamId, checkpointId);
        if (!Boolean.TRUE.equals(verdict.get("eligible"))) {
            throw ApiException.conflict("快照 " + checkpointId + " 不满足压缩资格："
                    + verdict.get("reason"));
        }
        return verdict;
    }
}
