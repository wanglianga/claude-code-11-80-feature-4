package com.flood.statemigrate.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 快照压缩归档：对经可恢复性证明（演练通过）且谱系完整的快照做 gzip 归档。
 * 归档包保留原文可解压能力，演练器可从归档恢复后再次核对输出摘要；
 * 任一成员演练不一致时整包冻结，派生包一并冻结。
 */
@Service
public class CompressionService {

    private final CompressionPackageRepo packageRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final SnapshotLineageRepo lineageRepo;
    private final CompressionEligibilityService eligibilityService;
    private final CompressionFreezeService freezeService;
    private final StreamService streamService;
    private final ObjectMapper om;

    public CompressionService(CompressionPackageRepo packageRepo, CheckpointManifestRepo checkpointRepo,
                              SnapshotLineageRepo lineageRepo,
                              CompressionEligibilityService eligibilityService,
                              CompressionFreezeService freezeService,
                              StreamService streamService, ObjectMapper om) {
        this.packageRepo = packageRepo;
        this.checkpointRepo = checkpointRepo;
        this.lineageRepo = lineageRepo;
        this.eligibilityService = eligibilityService;
        this.freezeService = freezeService;
        this.streamService = streamService;
        this.om = om;
    }

    @Transactional
    public CompressionPackage compress(Long streamId, List<Long> checkpointIds,
                                       String derivedFromPackageRef, boolean purgeOriginal, String note) {
        StreamDefinition stream = streamService.get(streamId);
        List<Long> ids = checkpointIds.stream().distinct().toList();
        List<String> rejectionReasons = new ArrayList<>();
        Map<String, Object> payloads = new LinkedHashMap<>();
        List<Map<String, Object>> schemas = new ArrayList<>();
        long originalBytes = 0;
        long compressedBytes = 0;

        CompressionPackage parent = null;
        if (derivedFromPackageRef != null && !derivedFromPackageRef.isBlank()) {
            parent = packageRepo.findByPackageRef(derivedFromPackageRef)
                    .orElseThrow(() -> ApiException.notFound("派生来源压缩包不存在: " + derivedFromPackageRef));
            if (parent.isFrozen() || freezeService.taintedByFrozenAncestor(parent)) {
                throw ApiException.conflict("来源压缩包 " + derivedFromPackageRef
                        + " 已冻结或继承了冻结祖先，禁止派生新压缩包");
            }
        }

        for (Long id : ids) {
            Map<String, Object> verdict = eligibilityService.evaluate(streamId, id);
            if (!Boolean.TRUE.equals(verdict.get("eligible"))) {
                rejectionReasons.add("检查点 " + id + ": " + verdict.get("reason"));
                continue;
            }
            CheckpointManifest cp = checkpointRepo.findById(id).orElseThrow();
            String json = cp.getStateSnapshotJson();
            if (json == null) {
                // 已归档快照：可从现有归档包中取回原文再入新包
                json = payloadFromAnyPackage(streamId, id);
            }
            if (json == null) {
                rejectionReasons.add("检查点 " + id + ": 无快照原文且无归档可解压");
                continue;
            }
            byte[] gz = gzip(json);
            payloads.put(String.valueOf(id), Base64.getEncoder().encodeToString(gz));
            originalBytes += json.getBytes(StandardCharsets.UTF_8).length;
            compressedBytes += gz.length;
            schemas.add(Map.of(
                    "checkpointId", id,
                    "stateSchema", cp.getStateSchema(),
                    "partitionNo", cp.getPartitionNo(),
                    "rulePackageVersion", cp.getRulePackageVersion()));
        }

        if (payloads.isEmpty()) {
            throw ApiException.unprocessable("没有满足压缩资格的检查点："
                    + String.join("；", rejectionReasons));
        }

        String ref = "cmp-" + streamId + "-" + System.nanoTime();
        CompressionPackage pkg = new CompressionPackage();
        pkg.setStreamId(streamId);
        pkg.setPackageRef(ref);
        pkg.setCheckpointIdsJson(writeJson(ids.stream().filter(i -> payloads.containsKey(String.valueOf(i))).toList()));
        pkg.setSchemasJson(writeJson(schemas));
        pkg.setStatus(CompressionStatus.ARCHIVED);
        pkg.setFrozen(false);
        pkg.setDerivedFromPackageRef(parent == null ? null : parent.getPackageRef());
        pkg.setOriginalBytes(originalBytes);
        pkg.setCompressedBytes(compressedBytes);
        pkg.setPayloadsJson(writeJson(payloads));
        pkg.setNote(note == null || note.isBlank()
                ? "压缩归档 " + payloads.size() + " 个已演练且谱系完整的快照"
                : note);

        for (String key : payloads.keySet()) {
            long cid = Long.parseLong(key);
            lineageRepo.findByCheckpointId(cid).ifPresent(l -> {
                l.setArchivedPackageRef(ref);
                lineageRepo.save(l);
            });
            if (!purgeOriginal) continue;
            CheckpointManifest cp = checkpointRepo.findById(cid).orElse(null);
            if (cp == null) continue;
            boolean latest = checkpointRepo
                    .findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(
                            streamId, cp.getPartitionNo())
                    .map(l -> l.getId().equals(cid)).orElse(false);
            if (latest) {
                rejectionReasons.add("检查点 " + cid + " 是分区 " + cp.getPartitionNo()
                        + " 的最新检查点，保留原文供在线恢复，不清理");
                continue;
            }
            cp.setStateSnapshotJson(null);
            checkpointRepo.save(cp);
        }
        if (!rejectionReasons.isEmpty()) {
            pkg.setNote(pkg.getNote() + "；保留原文未清理: " + String.join("；", rejectionReasons));
        }
        return packageRepo.save(pkg);
    }

    /** 从归档包解压指定检查点快照原文 */
    public String restorePayload(CompressionPackage pkg, Long checkpointId) {
        try {
            Map<String, String> payloads = om.readValue(pkg.getPayloadsJson(), new TypeReference<>() {});
            String b64 = payloads.get(String.valueOf(checkpointId));
            if (b64 == null) return null;
            return gunzip(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            throw new IllegalStateException("归档包解压失败: " + pkg.getPackageRef(), e);
        }
    }

    private String payloadFromAnyPackage(Long streamId, Long checkpointId) {
        for (CompressionPackage pkg : packageRepo.findByStreamIdOrderByIdDesc(streamId)) {
            if (!pkg.isFrozen() && pkg.getCheckpointIdsJson().contains(String.valueOf(checkpointId))) {
                String json = restorePayload(pkg, checkpointId);
                if (json != null) return json;
            }
        }
        return null;
    }

    public List<CompressionPackage> list(Long streamId) {
        return packageRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    public CompressionPackage get(Long streamId, String packageRef) {
        CompressionPackage pkg = packageRepo.findByPackageRef(packageRef)
                .orElseThrow(() -> ApiException.notFound("压缩包不存在: " + packageRef));
        if (!pkg.getStreamId().equals(streamId)) {
            throw ApiException.badRequest("压缩包 " + packageRef + " 不属于流 " + streamId);
        }
        return pkg;
    }

    public static byte[] gzip(String value) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(value.getBytes(StandardCharsets.UTF_8));
            gz.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String gunzip(byte[] bytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
