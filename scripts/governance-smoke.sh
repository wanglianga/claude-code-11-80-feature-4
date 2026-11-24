#!/usr/bin/env bash
# 检查点治理端到端冒烟：保留策略 → 谱系索引 → 可恢复性演练 → 压缩资格 → 归档
#                      → 压缩冻结（摘要不一致） → 谱系扩缩容映射 → 事故复原工作台
# 前置：应用已启动且演示数据为初始状态（如需重置见 README「重置演示数据」）。
# 建议单独执行（不依赖 upgrade 冒烟脚本的执行顺序）。
set -euo pipefail

BASE=${BASE:-http://127.0.0.1:8080}
AUTH=${AUTH:-operator:operator123}
STREAM_CODE=${STREAM_CODE:-FY-MAIN}
PASS=""

req() { curl -sf -u "$AUTH" -H 'Content-Type: application/json' "$@"; }
now_minus() { date -u -d "$1 minutes ago" +%Y-%m-%dT%H:%M:%SZ; }
step() { echo; echo "=== $* ==="; }

SID=$(req "$BASE/api/streams" | jq -r ".[] | select(.streamCode==\"$STREAM_CODE\") | .id")
[ -n "$SID" ] || { echo "找不到演示流 $STREAM_CODE"; exit 1; }

step "1. 检查点保留策略：预警等级基线"
req "$BASE/api/streams/$SID/retention-policies" | jq '[.[] | {warningLevel, retentionDays, requiredDrills, ruleChangeExtraDays, highImpactExtraDays}]'
req "$BASE/api/streams/$SID/retention-policies/evaluate" \
  | jq '{totalSnapshots, expired, withinRetention}'

step "2. 谱系索引：模式/位点/规则版本/当时生效的绕过规则"
req -X POST "$BASE/api/streams/$SID/lineage/rebuild" | jq .
CP0=$(req "$BASE/api/streams/$SID/checkpoints" | jq -r '[.[]|select(.partitionNo==0)]|max_by(.id)|.id')
CP2=$(req "$BASE/api/streams/$SID/checkpoints" | jq -r '[.[]|select(.partitionNo==2)]|max_by(.id)|.id')
req "$BASE/api/streams/$SID/lineage/$CP0" \
  | jq '{partitionNo, stateSchema, rulePackageVersion, warningLevel, lineageComplete, bypassRuleCount, directRestoreSafe}'
req "$BASE/api/streams/$SID/lineage" | jq 'length' | { read N; echo "谱系条目: $N"; }

step "3. 压缩资格（未演练 → 禁止压缩）"
req "$BASE/api/streams/$SID/checkpoints/$CP0/compression-eligibility" \
  | jq '{checkpointId, eligible, reason}'

step "4. 隔离演练：恢复输出摘要一致 → 可恢复性证明成立（ORANGE 要求 2 次）"
for run in 1 2; do
  DRILL=$(req -X POST "$BASE/api/streams/$SID/recovery-drills" -d "{\"checkpointId\": $CP0, \"isolatedEnv\": \"iso-smoke-$run\"}")
  echo "$DRILL" | jq '{id, status, digestMatch, restoreSource, expectedDigest: .expectedDigest[:16], actualDigest: .actualDigest[:16]}'
  echo "$DRILL" | jq -e '.status=="PASSED" and .digestMatch==true' >/dev/null
done

step "5. 达到演练次数后压缩资格"
req "$BASE/api/streams/$SID/checkpoints/$CP0/compression-eligibility" \
  | jq '{checkpointId, eligible, passedDrills, requiredDrills, reason}'

step "6. 压缩归档（演练通过且谱系完整）"
PKG=$(req -X POST "$BASE/api/streams/$SID/compression-packages?purgeOriginal=false" \
  -d "{\"checkpointIds\": [$CP0], \"note\": \"冒烟归档\"}")
echo "$PKG" | jq '{packageRef, status, originalBytes, compressedBytes, frozen}'
REF=$(echo "$PKG" | jq -r '.packageRef')

step "7. 摘要不一致快照：演练失败 → 冻结（模拟事故快照）"
# 构造一个降雨累计与事件不符的快照：p3 已有事件，构造窗口与可见事件一致但累计量被篡改
BAD=$(req -X POST "$BASE/api/streams/$SID/checkpoints" -d "{
  \"partitionNo\": 3, \"consumeOffset\": 9999,
  \"windowWatermark\": \"$(now_minus 5)\",
  \"stateSnapshotJson\": \"{\\\"gridId\\\":\\\"R-004\\\",\\\"windowStart\\\":\\\"$(now_minus 130)\\\",\\\"windowEnd\\\":\\\"$(now_minus 10)\\\",\\\"rain2hAcc\\\":999.0,\\\"upstreamInflowAcc\\\":300.0,\\\"deviceHealth\\\":0.95,\\\"pumpRuntimeMin\\\":0,\\\"riskLevel\\\":\\\"LOW\\\"}\",
  \"outputBatchId\": \"bad-batch-smoke\", \"rulePackageVersion\": \"v1.0.0\",
  \"stateSchema\": \"GRID_WINDOW_V1\", \"status\": \"COMPLETE\"}")
BAD_ID=$(echo "$BAD" | jq -r '.id')
BAD_DRILL=$(req -X POST "$BASE/api/streams/$SID/recovery-drills" -d "{\"checkpointId\": $BAD_ID, \"isolatedEnv\": \"iso-smoke\"}")
echo "$BAD_DRILL" | jq '{id, status, digestMatch}'
echo "$BAD_DRILL" | jq -e '.status=="FAILED" or .status=="BLOCKED"' >/dev/null
req "$BASE/api/streams/$SID/lineage/$BAD_ID" | jq '{frozen, frozenReason}'
req "$BASE/api/streams/$SID/checkpoints/$BAD_ID/compression-eligibility" | jq '{eligible, reason}'

step "8. 谱系扩缩容：检查点之间发生分区拆分 → 旧分区号不可直接恢复"
SCALE_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
req -X POST "$BASE/api/streams/$SID/scale-events" -d "{
  \"fromPartitions\": 3, \"toPartitions\": 4,
  \"mappingJson\": \"[{\\\"from\\\":0,\\\"to\\\":0},{\\\"from\\\":1,\\\"to\\\":1},{\\\"from\\\":2,\\\"to\\\":2},{\\\"from\\\":2,\\\"to\\\":3,\\\"split\\\":true}]\",
  \"occurredAt\": \"$SCALE_AT\", \"reason\": \"冒烟：p2 拆分\"}" | jq '{fromPartitions, toPartitions, occurredAt}'
req "$BASE/api/streams/$SID/lineage?partitionNo=2" \
  | jq '[.[] | {checkpointId, partitionNo, directRestoreSafe, restoreMapping, mappingNotes}]' | head -40

step "9. 生成新模式（PUMP_REACH_ROAD_V2）检查点，使事故调查窗跨两种状态模式"
req -X POST "$BASE/api/streams/$SID/proofs" -d '{}' >/dev/null
req -X POST "$BASE/api/streams/$SID/migration-plans" >/dev/null
req -X POST "$BASE/api/streams/$SID/migration-plans/execute-all" >/dev/null
req -X POST "$BASE/api/streams/$SID/lineage/rebuild" >/dev/null
req "$BASE/api/streams/$SID/lineage" | jq 'group_by(.stateSchema) | map({schema: .[0].stateSchema, count: length})'

step "10. 事故复原工作台：调查窗跨模式/跨空档 → 组合相邻快照并标注空档"
WIN_START=$(now_minus 200)
WIN_END=$(now_minus 5)
INC=$(req -X POST "$BASE/api/streams/$SID/incident-restores" -d "{
  \"windowStart\": \"$WIN_START\", \"windowEnd\": \"$WIN_END\"}")
echo "$INC" | jq '{id, status, schemaTransition}'
INC_ID=$(echo "$INC" | jq -r '.id')
echo "$INC" | jq '.resultJson | fromjson | {status, schemaTransition, minimalPartitions, segmentCount: (.segments|length), gapCount: (.gaps|length), notes}'
echo "$INC" | jq '.resultJson | fromjson | .restoreOrder' | jq '[.[] | {step, partitionNo, stateSchema}]'


step "11. 执行最小必要分区恢复（仅取回可还原分段）"
req -X POST "$BASE/api/streams/$SID/incident-restores/$INC_ID/restore" \
  | jq '{status, minimalPartitions, restored: (.restoredSegments|length), skipped: (.skippedSegments|length), schemaTransition}'

step "12. 压缩包与演练状态汇总"
req "$BASE/api/streams/$SID/compression-packages" | jq '[.[] | {packageRef, status, frozen}]'
req "$BASE/api/streams/$SID/recovery-drills" | jq 'group_by(.status) | map({status: .[0].status, count: length})'

echo
echo "GOVERNANCE_SMOKE_OK"
