package com.flood.statemigrate.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * 可恢复性演练器：在隔离环境从指定检查点恢复状态，并用检查点当时绑定的规则包
 * 重放输入位点区间得到期望输出摘要，两者比对即"可恢复性证明"。
 * 摘要不一致时冻结该快照及其派生压缩包——证明未成立前不允许压缩。
 */
@Service
public class DrillService {

    private final RecoveryDrillRepo drillRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final StreamEventRepo eventRepo;
    private final SnapshotLineageRepo lineageRepo;
    private final CompressionPackageRepo packageRepo;
    private final StreamService streamService;
    private final LineageService lineageService;
    private final RulePackageService rulePackageService;
    private final AggregationService aggregationService;
    private final RuleEngine ruleEngine;
    private final SnapshotDigest digest;
    private final CompressionFreezeService freezeService;
    private final ObjectMapper om;

    public DrillService(RecoveryDrillRepo drillRepo, CheckpointManifestRepo checkpointRepo,
                        StreamEventRepo eventRepo, SnapshotLineageRepo lineageRepo,
                        CompressionPackageRepo packageRepo, StreamService streamService,
                        LineageService lineageService, RulePackageService rulePackageService,
                        AggregationService aggregationService, RuleEngine ruleEngine,
                        SnapshotDigest digest, CompressionFreezeService freezeService, ObjectMapper om) {
        this.drillRepo = drillRepo;
        this.checkpointRepo = checkpointRepo;
        this.eventRepo = eventRepo;
        this.lineageRepo = lineageRepo;
        this.packageRepo = packageRepo;
        this.streamService = streamService;
        this.lineageService = lineageService;
        this.rulePackageService = rulePackageService;
        this.aggregationService = aggregationService;
        this.ruleEngine = ruleEngine;
        this.digest = digest;
        this.freezeService = freezeService;
        this.om = om;
    }

    @Transactional
    public RecoveryDrill drill(Long streamId, Long checkpointId, String isolatedEnv) {
        StreamDefinition stream = streamService.get(streamId);
        CheckpointManifest cp = checkpointRepo.findById(checkpointId)
                .orElseThrow(() -> ApiException.notFound("检查点不存在: " + checkpointId));
        if (!streamId.equals(cp.getStreamId())) {
            throw ApiException.badRequest("检查点 " + checkpointId + " 不属于流 " + streamId);
        }
        String env = isolatedEnv == null || isolatedEnv.isBlank()
                ? "isolated-drill-" + streamId : isolatedEnv;

        String snapshotJson = cp.getStateSnapshotJson();
        String source = "STATE_SNAPSHOT";
        if (snapshotJson == null) {
            // 原文已归档清理：从归档包解压后核对，验证归档本身仍可恢复
            String fromArchive = payloadFromArchive(streamId, checkpointId);
            if (fromArchive == null) {
                return persistBlocked(streamId, cp, env,
                        "检查点无状态快照原文且无可用归档，无法在隔离环境恢复");
            }
            snapshotJson = fromArchive;
            source = "ARCHIVE";
        }
        return runDrill(stream, cp, env, snapshotJson, source);
    }

    private RecoveryDrill runDrill(StreamDefinition stream, CheckpointManifest cp,
                                   String env, String snapshotJson, String source) {
        JsonNode snapshot = digest.read(snapshotJson);
        if (snapshot == null) {
            return persistBlocked(stream.getId(), cp, env, "快照原文非法 JSON，无法在隔离环境恢复");
        }
        SnapshotLineage lineage = lineageRepo.findByCheckpointId(cp.getId())
                .orElseGet(() -> lineageService.ensureLineage(stream, cp));

        Instant windowStart = digest.windowStart(snapshot);
        Instant windowEnd = digest.windowEnd(snapshot);
        if (windowStart == null || windowEnd == null) {
            return persistBlocked(stream.getId(), cp, env,
                    "快照缺少 windowStart/windowEnd，无法重放输入位点区间");
        }

        List<StreamEvent> events = eventRepo
                .findByStreamIdAndPartitionNoAndEventTimeBetweenOrderByEventTimeAsc(
                        stream.getId(), cp.getPartitionNo(), windowStart, windowEnd);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkpointId", cp.getId());
        report.put("partitionNo", cp.getPartitionNo());
        report.put("windowStart", windowStart);
        report.put("windowEnd", windowEnd);
        report.put("stateSchema", cp.getStateSchema());
        report.put("rulePackageVersion", cp.getRulePackageVersion());
        report.put("isolatedEnv", env);

        if (events.isEmpty()) {
            return persistBlocked(stream.getId(), cp, env,
                    "隔离环境内输入位点区间无原始事件，无法重放期望输出");
        }

        int windowHours = SnapshotDigest.windowHours(windowStart, windowEnd);
        AggInput expectedAgg = aggregationService.aggregate(
                events, stream.getWaterLevelBaseline(), windowEnd, windowHours);
        double replayedRuntime = aggregationService.pumpRuntimeMin(
                aggregationService.pumpTransitions(events), windowStart, windowEnd);

        // 期望输出摘要：快照绑定的规则包在重放输入上的结论
        String expectedDigest;
        try {
            RulePackage pkg = rulePackageService.get(stream.getId(), cp.getRulePackageVersion());
            RiskResult expectedRisk = pkg.getKind() == RuleKind.FIXED_THRESHOLD
                    ? ruleEngine.evaluateOld(aggregationService.readTree(pkg.getConfigJson()), expectedAgg)
                    : ruleEngine.evaluateNew(aggregationService.readTree(pkg.getConfigJson()), expectedAgg);
            Map<String, Double> expectedFields = new LinkedHashMap<>();
            expectedFields.put("rain2h", expectedAgg.rain2h());
            expectedFields.put("pumpRuntimeMin", replayedRuntime);
            expectedFields.put("credibility", expectedAgg.credibility());
            expectedDigest = digest.digest(expectedFields);
            report.put("expectedCanonical", digest.canonical(expectedFields));
            report.put("expectedRiskLevel", expectedRisk.level());
            report.put("expectedRiskScore", AggregationService.round2(expectedRisk.score()));
            report.put("replayedEvents", events.size());
            report.put("replayedFromOffset", expectedAgg.fromOffset());
            report.put("replayedToOffset", expectedAgg.toOffset());
            report.put("replayedPumpRuntimeMin", AggregationService.round2(replayedRuntime));
        } catch (ApiException e) {
            return persistBlocked(stream.getId(), cp, env,
                    "快照绑定的规则包不可用（" + cp.getRulePackageVersion() + "），无法重建期望输出：" + e.getMessage());
        }

        // 实际输出摘要：从隔离环境恢复出的快照状态
        Map<String, Double> recovered = digest.comparableFields(snapshot);
        if (recovered.size() < 3) {
            return persistBlocked(stream.getId(), cp, env,
                    "快照可比对状态字段不全（需要 rain2hAcc / pumpRuntimeMin / deviceHealth），无法核对输出摘要");
        }
        String actualDigest = digest.digest(recovered);
        boolean match = expectedDigest.equals(actualDigest);

        RecoveryDrill drill = new RecoveryDrill();
        drill.setStreamId(stream.getId());
        drill.setCheckpointId(cp.getId());
        drill.setPartitionNo(cp.getPartitionNo());
        drill.setIsolatedEnv(env);
        drill.setExpectedDigest(expectedDigest);
        drill.setActualDigest(actualDigest);
        drill.setDigestMatch(match);
        drill.setInputOffsetFrom(expectedAgg.fromOffset());
        drill.setInputOffsetTo(expectedAgg.toOffset());
        drill.setRestoreSource(source.startsWith("ARCHIVE")
                ? source : "STATE_SNAPSHOT");
        drill.setStatus(match ? DrillStatus.PASSED : DrillStatus.FAILED);

        report.put("expectedDigest", expectedDigest);
        report.put("actualDigest", actualDigest);
        report.put("actualCanonical", digest.canonical(recovered));
        report.put("digestMatch", match);
        report.put("restoreSource", drill.getRestoreSource());
        report.put("message", match
                ? "隔离环境恢复输出摘要与期望摘要一致，可恢复性证明成立"
                : "隔离环境恢复输出摘要与期望摘要不一致：该快照不具备可恢复性证明，"
                    + "快照及其派生压缩包被冻结，禁止继续压缩归档");
        drill.setReportJson(writeJson(report));
        drillRepo.save(drill);

        if (!match) {
            freeze(cp.getId(), lineage, "演练 " + drill.getId() + " 输出摘要不一致（expected="
                    + expectedDigest.substring(0, 12) + "…, actual=" + actualDigest.substring(0, 12) + "…）");
        }
        return drill;
    }

    /** 从该检查点曾参与的归档包中解压快照原文（按包由新到旧尝试） */
    private String payloadFromArchive(Long streamId, Long checkpointId) {
        for (CompressionPackage pkg : packageRepo.findByStreamIdOrderByIdDesc(streamId)) {
            if (pkg.isFrozen() || !pkg.getCheckpointIdsJson().contains(String.valueOf(checkpointId))) continue;
            try {
                Map<String, String> payloads = om.readValue(pkg.getPayloadsJson(),
                        new TypeReference<Map<String, String>>() {});
                String b64 = payloads.get(String.valueOf(checkpointId));
                if (b64 != null) {
                    return CompressionService.gunzip(Base64.getDecoder().decode(b64));
                }
            } catch (Exception e) {
                // 当前包损坏，尝试更早的归档
            }
        }
        return null;
    }

    private RecoveryDrill persistBlocked(Long streamId, CheckpointManifest cp, String env, String reason) {
        RecoveryDrill drill = new RecoveryDrill();
        drill.setStreamId(streamId);
        drill.setCheckpointId(cp.getId());
        drill.setPartitionNo(cp.getPartitionNo());
        drill.setIsolatedEnv(env);
        drill.setStatus(DrillStatus.BLOCKED);
        drill.setDigestMatch(false);
        drill.setInputOffsetFrom(cp.getConsumeOffset());
        drill.setInputOffsetTo(cp.getConsumeOffset());
        drill.setReportJson(writeJson(Map.of("blocked", true, "reason", reason,
                "checkpointId", cp.getId(), "partitionNo", cp.getPartitionNo())));
        return drillRepo.save(drill);
    }

    /** 冻结快照及其派生压缩包（含沿派生链传递的归档包） */
    @Transactional
    public void freeze(Long checkpointId, SnapshotLineage lineage, String reason) {
        lineage.setFrozen(true);
        lineage.setFrozenReason(reason);
        lineageRepo.save(lineage);
        String id = String.valueOf(checkpointId);
        for (CompressionPackage pkg : packageRepo.findByStreamIdOrderByIdDesc(lineage.getStreamId())) {
            boolean member = pkg.getCheckpointIdsJson() != null
                    && pkg.getCheckpointIdsJson().contains(id);
            boolean owner = lineage.getArchivedPackageRef() != null
                    && lineage.getArchivedPackageRef().equals(pkg.getPackageRef());
            if (member || owner) {
                freezeService.freezeWithDescendants(lineage.getStreamId(), pkg.getPackageRef(), reason);
            }
        }
    }

    /** 最近一次演练结论 */
    public RecoveryDrill latest(Long streamId, Long checkpointId) {
        return drillRepo.findByStreamIdAndCheckpointIdOrderByIdDesc(streamId, checkpointId)
                .stream().findFirst().orElse(null);
    }

    public List<RecoveryDrill> list(Long streamId) {
        return drillRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    public RecoveryDrill get(Long drillId) {
        return drillRepo.findById(drillId)
                .orElseThrow(() -> ApiException.notFound("演练记录不存在: " + drillId));
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
