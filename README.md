# 防汛平台状态迁移与规则升级服务

某市防汛平台在汛期中途把固定阈值风险规则升级为多因子规则（同时考虑**上游水位趋势、泵站启停滞后、传感器可信度**）。
旧作业保存的是**网格窗口状态**，新规则需要**泵站—河段—道路三层中间状态**。本服务提供状态迁移与规则升级的全流程治理能力：

- **流定义登记表**：输入主题、事件时间字段、水位基准、状态模式、规则包版本、分区→道路/泵站/河段映射；
- **检查点清单**：每个分区的消费位点、窗口水印、状态快照、输出批次，并提供检查点比对（位点/水印滞后与就绪度）；
- **状态语义证明器**：比较旧新字段、聚合粒度与依赖关系，给出旧状态确实可转换的守恒条件；
- **迁移计划器**：按分区选择 `ADAPT`（经证明的状态适配）/ `REPLAY`（限定时间窗回放）/ `MANUAL`（人工裁定），支持混合路线；
- **双轨校验器**：旧新规则在同一输入截点并行计算并量化风险差异；
- **切换闸门**：仅当关键分区满足证据要求且覆盖率达标时发布新输出，否则维持旧输出并标记待验证；
- **回退控制器**：保留旧规则只读快照，撤回指定范围内的新输出；
- **事件证据账**：每次风险结论记录输入、规则与状态版本，区分 `OLD_OUTPUT` / `NEW_CANDIDATE` / `NEW_OUTPUT` / `RETRACTED`；
- **检查点保留策略器**：按汛情预警等级（蓝/黄/橙/红）定义基线保留期与要求演练次数，跨越规则包版本变更、产出 HIGH 影响输出时叠加延长；
- **可恢复性演练器**：在隔离环境从指定检查点恢复，用该快照绑定的规则包重放输入位点区间得到期望输出摘要，与恢复出的实际摘要比对；
- **快照谱系索引**：关联快照、状态模式、输入位点、当时生效的规则包版本与临时绕过规则；扩缩容发生时记录分区映射，禁止假设旧分区号可直接恢复；
- **压缩资格判定器**：以可恢复性证明驱动压缩——仅对已演练通过、通过次数达标且谱系完整的快照允许 gzip 归档，不按日期机械删除；
- **事故复原工作台**：按调查时间窗组合相邻快照，给出最小必要分区、分段恢复计划与不可还原空档；跨状态模式时显式标注须组合两种模式。

## 技术栈

Java 25 · Spring Boot 4.0 · Spring Data JPA · PostgreSQL 18 · springdoc-openapi（Swagger UI）· Maven

## 快速开始

### 方式一：Docker Compose 一键启动（应用 + PostgreSQL）

```bash
docker compose up --build
```

启动后访问 http://localhost:8080/swagger-ui.html 。

### 方式二：本地开发

前置：JDK 25、Maven 3.9+、PostgreSQL（库/用户/密码均为 `ccdev`，见 `src/main/resources/application.yml`，可用 `DB_URL/DB_USER/DB_PASSWORD` 覆盖）。

```bash
# 运行测试（使用 ccdev_test 库，首次需创建：CREATE DATABASE ccdev_test OWNER ccdev;）
mvn test

# 打包并启动（首次启动自动建表并写入演示数据）
mvn package -DskipTests
java -jar target/state-migration-service-1.0.0.jar
```

### 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `operator` | `operator123` | OPERATOR |
| `admin` | `admin123` | ADMIN, OPERATOR |

Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`）无需登录；`/api/**` 需上述账号。

## 接口一览

| 模块 | 方法与路径 | 说明 |
| --- | --- | --- |
| 流定义 | `POST /api/streams`、`GET /api/streams[/{id}]` | 登记/查询流定义 |
| 规则包 | `POST /api/streams/{id}/rule-packages`、`GET .../rule-packages` | 登记/二次修订；多因子修订使相关比较与证明过期并重新绑定目标版本 |
| 流事件 | `POST /api/streams/{id}/events`、`GET .../events` | 事件写入（位点按分区自增）与查询 |
| 检查点 | `POST /api/streams/{id}/checkpoints`、`GET .../checkpoints` | 登记/查询检查点清单 |
| 检查点比对 | `GET /api/streams/{id}/checkpoint-compare` | 位点/水印滞后、快照与迁移就绪度比对 |
| 证明器 | `POST /api/streams/{id}/proofs`、`GET /api/proofs/{id}` | 生成状态语义证明（守恒条件） |
| 迁移编排 | `POST /api/streams/{id}/migration-plans`、`POST .../execute-all`、`POST /api/migration-plans/{id}/execute`、`POST /api/migration-plans/{id}/adjudicate` | 计划生成/执行/人工裁定 |
| 双轨校验 | `POST /api/streams/{id}/dual-track-runs`、`GET /api/dual-track-runs/{id}/diffs` | 同截点并行计算与差异量化 |
| 切换闸门 | `POST /api/streams/{id}/gate/evaluate`、`POST .../gate/release`、`GET .../gate` | 评估/发布/状态 |
| 回退控制 | `POST /api/streams/{id}/rollbacks`、`GET .../rollbacks` | 撤回输出范围、旧快照只读 |
| 证据账 | `GET /api/streams/{id}/evidence?partitionNo=&kind=` | 按分区/类别查询证据 |
| 保留策略 | `POST /api/streams/{id}/retention-policies`、`GET .../retention-policies`、`GET .../retention-policies/evaluate` | 预警等级保留期与逐快照评估 |
| 谱系索引 | `GET /api/streams/{id}/lineage?partitionNo=&stateSchema=&from=&to=`、`GET .../lineage/{checkpointId}`、`POST .../lineage/rebuild` | 谱系检索/详情/补建 |
| 谱系来源 | `POST/GET /api/streams/{id}/scale-events`、`POST/GET /api/streams/{id}/bypass-rules` | 扩缩容事件与临时绕过规则 |
| 可恢复性演练 | `POST /api/streams/{id}/recovery-drills`、`GET .../recovery-drills[/{id}]` | 隔离环境恢复并核对输出摘要 |
| 压缩资格/归档 | `GET /api/streams/{id}/checkpoints/{cpId}/compression-eligibility`、`GET .../compression-eligibility`、`POST .../compression-packages?purgeOriginal=`、`GET .../compression-packages[/{ref}]` | 资格判定与归档 |
| 事故复原 | `POST /api/streams/{id}/incident-restores`、`GET .../incident-restores[/{id}]`、`POST .../incident-restores/{id}/restore` | 最小必要分区恢复与不可还原空档 |

## 迁移路线选择语义

- **状态适配（ADAPT）**：旧聚合拆解/提升为三层状态，延迟小。仅当守恒条件满足时选用：
  - `PUMP_ON_OFF_CONSERVATION`：由启停事件推导的窗口内泵运行时长须与快照累计量一致（容差 1 分钟）；
  - `WINDOW_GRANULARITY_MATCH`：旧窗口须为约两小时，与新规则累计粒度一致。
  快照缺失启停事件时**适配器必须拒绝该快照，禁止以零值填充缺失启停区间**；执行时若发现累计量不守恒，计划器自动**转为限定时间窗回放**。
- **限定窗口回放（REPLAY）**：从冻结位点对受影响分区回放原始事件重算三层状态，语义最直接；回放窗口受事件保留范围约束，保留不足时降级为**人工裁定（MANUAL）**。
- 计划器依据状态可逆性（证明结论）、事件保留范围与汛情时效（关键分区优先适配）为各分区选择或混合两条路线。

## 关键业务规则

1. 新规则把某道路由低风险改为高风险、而旧作业尚未完成该分区检查点时，闸门**维持旧输出**并把该分区标记为**待验证**（`pendingVerification`），补齐检查点后方可放行。
2. 双轨期间规则二次修订（如可信度规则 v2.0.0→v2.1.0）：相关双轨比较与适配证明**过期**，引用旧证明的计划置为 `STALE`，目标版本**重新绑定**到新规则包，须重新证明与双轨后才能发布。
3. 回放分区越过切换水印时，证据账区分：已发布新输出（越水印部分**撤回**为 `RETRACTED`）、本次回放结果仅作**新候选**（`NEW_CANDIDATE`）、旧输出（`OLD_OUTPUT`）保持不变。
4. 回退保留旧规则**只读快照**，撤回 `[fromOffset, toOffset]` 范围内的新输出，闸门回到 `HOLD`，流恢复旧规则服务。

## 检查点治理规则（保留策略 / 演练 / 谱系 / 压缩）

1. **保留期**：`预警等级基线天数 + 规则变更延长 + 高影响输出延长`。等级基线内置于 `WarningLevel`（蓝 7/黄 30/橙 90/红 180 天），可按流覆盖；快照跨越规则包版本边界时叠加 `ruleChangeExtraDays`，证据账显示 HIGH 输出或快照本身为 RED 时叠加 `highImpactExtraDays`。保留期只决定"可清理窗口"，是否压缩仍以可恢复性证明为准，**不按日期机械删除**。
2. **可恢复性证明**：隔离演练用快照绑定的规则包重放其输入位点区间，得到期望摘要（rain2h / pumpRuntimeMin / credibility），与恢复出的快照摘要比对。**摘要不一致时该快照与其派生压缩包被冻结**，压缩资格判定器拒绝压缩/派生。
3. **谱系索引**：每条检查点登记 `stateSchema`、`inputOffsetFrom/To`、`rulePackageVersion`、`windowStart/End`、`previousCheckpointId`、当时生效的 `bypassRules`。快照创建后若发生扩缩容，检索与复原按 `scale-events` 的映射逐次重写分区号，`directRestoreSafe=false` 并给出 `restoreMapping`；**不假设旧分区号可直接恢复**（拆分出的分区在映射中显式标注 `split`）。
4. **压缩资格**：`谱系完整 && 未冻结 && 最近一次演练 PASSED && 通过次数 ≥ 策略要求 && 快照仍可恢复`。归档包保留 gzip 原文可解压，演练器可从归档恢复后再次核对摘要；归档可清理较早检查点原文，但保留**每分区最新检查点**原文供在线恢复。
5. **事故复原**：按调查时间窗逐分区组合相邻快照；跨两种状态模式时 `schemaTransition=true` 并按 `GRID_WINDOW_V1 → PUMP_REACH_ROAD_V2` 顺序给出 `restoreOrder`；无覆盖、冻结、快照原文缺失的时段写入 `gaps`（含起止时刻），**不得以相邻状态外推填充**；`minimalPartitions` 只含确实有可恢复快照的分区。

## 演示数据（首次启动自动写入）

流 `FY-MAIN`（4 个分区，p0/p1/p2 为关键分区）：

| 分区 | 道路 | 初始状态 | 预期路线 |
| --- | --- | --- | --- |
| 0 | R-001 | 检查点完成，启停守恒满足 | ADAPT |
| 1 | R-002 | 快照缺失启停事件 | REPLAY（禁止零值填充） |
| 2 | R-003 | 旧作业检查点未完成；新规则 LOW→HIGH | REPLAY + 闸门待验证 |
| 3 | R-004 | 检查点完成，空启停守恒满足 | ADAPT |

## 验证方式（本仓库交付前实际执行过的验证）

```bash
# 1) 自动化测试：31 个用例全部通过（原有 17 个 + 保留策略/演练压缩/谱系/事故复原 14 个）
mvn test

# 2) 端到端冒烟（应用已启动、演示数据为初始状态）：12 步完整升级流程，输出 SMOKE_OK
scripts/smoke.sh

# 3) 检查点治理冒烟（保留策略 → 谱系 → 演练 → 压缩资格 → 归档 → 冻结 → 扩缩容映射 → 事故复原）
scripts/governance-smoke.sh

# 4) 重复冒烟前先重置演示数据（清空业务表并重启应用重新播种）
scripts/reset-db.sh && scripts/smoke.sh
```

冒烟流程：证明 → 迁移编排（p0/p3 适配、p1/p2 回放）→ 执行 → 双轨（p2 低→高升级）→ 闸门待验证 →
补齐 p2 检查点 → 规则二次修订 v2.1.0（证明/比较过期、计划 STALE）→ 重新绑定 → 发布新输出 →
p1 回放越过切换水印（已发布输出撤回、结果仅作候选）→ 回退（快照只读、范围撤回）→ 证据账分类统计。

`governance-smoke.sh` 治理流程（输出 `GOVERNANCE_SMOKE_OK`）：保留策略基线 → 谱系索引（含绕过规则）→
未演练时拒绝压缩 → 隔离演练两次通过 → 达到演练次数后允许归档（gzip 且体积下降）→ 构造摘要不一致快照，
演练 FAILED 并冻结、资格判定拒绝 → 登记扩缩容（p2 拆分）后 `directRestoreSafe=false` 且映射为 `[2,3]` →
生成新模式检查点使调查窗跨 `GRID_WINDOW_V1`/`PUMP_REACH_ROAD_V2` → 事故复原给出跨模式 `restoreOrder`、
最小必要分区与空档 → 执行最小恢复（仅取回可还原分段）。

治理冒烟与本轮验证均使用实际启动的 jar（PostgreSQL `ccdev`），截图字段见脚本输出；脚本本身已改为 LF 行尾以便在容器内直接执行。

> 说明：本开发容器无 Docker 守护进程，`docker compose up --build` 未在本环境实际执行；
> Dockerfile/Compose 使用标准基础镜像（`maven:3.9-eclipse-temurin-25`、`eclipse-temurin:25-jre`、`postgres:18`），
> 应用本体已通过上述 `mvn test`、`scripts/smoke.sh` 与 `scripts/governance-smoke.sh` 在相同 jar 上验证。

## 目录结构

```
src/main/java/com/flood/statemigrate/
├── domain/      # 实体与枚举（流定义/规则包/检查点/证明/计划/双轨/闸门/回退/证据/事件/
│                #   保留策略/谱系/扩缩容/绕过规则/演练/压缩包/事故复原）
├── repo/        # Spring Data JPA 仓库
├── service/     # 证明器、计划器（适配+回放）、双轨、闸门、回退、证据账、聚合与规则引擎；
│                #   保留策略、谱系、演练、压缩资格与归档冻结、事故复原
├── web/         # REST 控制器与请求 DTO
├── config/      # 安全、OpenAPI、演示数据种子
└── error/       # 统一异常处理
scripts/         # smoke.sh 升级冒烟、governance-smoke.sh 检查点治理冒烟、reset-db.sh 演示数据重置
docker/          # Maven 镜像仓库配置（构建加速）
Dockerfile · docker-compose.yml
```
