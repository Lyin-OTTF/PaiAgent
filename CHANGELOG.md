# 版本说明 / Changelog

本文件汇总 PaiAgent 各发布版本的变更说明。对应 Git 标签（`v2.0.0`～`v2.4.0`）均已推送到 `origin`，可在 GitHub 的 Tags / Releases 页面查看每条标签自带的说明。

---

## v2.5.1 — HNSW 索引同 id 覆盖写越界修复（2026-10-25）

**🔴 高危：覆盖写触发 `ArrayIndexOutOfBoundsException`，整次 upsert 中断**

现象：对已存在的 id 覆盖写时，若被覆盖的正是当前入口节点，`upsert` 抛
`ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1`
（`HnswVectorIndex.greedyClosest:223` ← `upsert:114`）。知识库重建、同一片段重新入库走的都是覆盖写，
一旦命中就是整批索引中断；且是否触发取决于随机层级与插入顺序，手工复现不稳定。

根因（三处叠加，缺一不可）：
1. `upsert` 先 `nodes.remove(id)` 再插入。若覆盖的是 entry，`entry` 会悬空指向已移出 `nodes` 的旧对象，
   其 links 里的 id 解析到的却是本次新插入、层级更低的新节点。
2. `greedyClosest` 把 current 移到邻居后，仍按入参 level 读 `current.links[level]`。邻居层级可能低于
   level（覆盖写残留链接、`remove` 只打 tombstone 不重连），links 数组长度不足即越界。
3. `beamSearch` 未过滤"本层不存在的节点"，同样会按 level 读 `links[level]`。

修复：
- 覆盖写改为「旧节点作导航起点、新节点就位后替换」。新节点必须先 `put`——否则反向连边后
  `pruneNeighbors` 会把刚加的边当成悬空 id 剪掉（试过延后 put，recall 直接掉到 0.03）；
  导航沿用旧节点引用；连边种子改取旧节点在该层的邻居（此时 id 已指向无链接的新节点，
  用自身当种子会被 `selectNeighbors` 当自环过滤，退化成孤立点）；最后修正 `entry` 指向存活节点。
- `greedyClosest` 的有效层级逐跳收紧为 `min(level, node.level)`，并把"同一次遍历内连续改进"改为
  "每轮只移动一步最优邻居"，保证移动与层级收紧成对发生，不会用旧 lv 读新 current。
- `beamSearch` 的种子收集与邻居扩展均跳过 `level > node.level` 的节点。

**测试**
- `HnswVectorIndexTest` 新增 `shouldRemainQueryableAfterOverwritingEveryId`：400 点插入 + 400 点全量覆盖写，
  断言容量不变、覆盖后抽样 10 个点仍可检索。该场景此前完全没有用例覆盖——原有的 `shouldOverwriteSameId`
  只有 1~2 个点，图里不存在层级差，永远触发不到这条路径。
- 质量门：全量 **502** 项测试通过（501 → +1），含 `VectorIndexBenchmarkTest` 的 recall 断言。

---

## v2.5.0 — 迭代10：SSRF 收口与出站响应加固（2026-10-19）

把「上游不可信」这条主线从 WebFetch / TTS 补齐到全部出站通道，并收口响应体读取与异常回显。

**🔴 高危：最后一个未设防的出站通道**
- `ChatClientFactory.createClient` 补 `UrlSafetyValidator.assertSafeOutbound(apiUrl)`。apiUrl 来自节点配置或全局配置，导入/共享的工作流即可携带任意值，而建连即带着 apiKey 向该地址发请求——此前 WebFetch 工具与 Step-TTS 都已校验，唯独 LLM 主路径没有，等于内网地址唯一未设防的出站通道（可打 169.254.169.254 云元数据窃取临时凭证）。
- `AgnesVideoClient` / `OpenAiCompatibleImageClient` / `StepFunImageClient` / `VolcengineAgentPlanClient` 四个 provider 客户端的建连方法同样补校验，与 SafeHttpFetcher / TTS / LLM 同一策略。

**🟡 中危：响应体无上限与连接泄漏**
- 四个客户端的 `readAllBytes()` 改为 `BoundedStreamReader.readCapped(is, 16MB)`，超限显式抛错、不做静默截断（半截 JSON/二进制被当成完整响应，错误更难排查）。
- 补 try-with-resources + `finally { conn.disconnect(); }`：修复前流未关闭、连接未断开，keep-alive 连接会泄漏到 GC。

**🟡 中危：异常细节回显**
- `ExecutionController` / `GovernanceController` / `LLMConfigController` 不再无条件回显 `e.getMessage()`：业务异常（`IllegalArgumentException` / `IllegalStateException`）保留可读文案，未预期异常只回通用文案，细节仅入日志，与 `GlobalExceptionHandler` 策略一致。
- `LLMGlobalConfigService.setDefaultConfig` 的 `RuntimeException` 改为 `IllegalArgumentException`，使控制器能区分业务异常与未预期异常。

**测试**
- 新增 `ProviderClientSsrfGuardTest`：反射直调四个客户端的私网建连方法，锁定「云元数据 / 回环 / 私网 / 非 http(s) 协议在建连前即被拒绝」。安全属性必须有测试锁定，否则重构时弄掉校验没有任何用例会挂。
- 新增 `BoundedStreamReaderTest`（5 例）。
- `ChatClientFactoryTest` 改用 RFC 5737 文档段 192.0.2.0/24 作为「公网形态」地址，字面量 IP 不触发 DNS，保持离线可跑。

**质量门**：全量 **501** 项测试通过（491 → +10）。

---

## v2.4.0 — 迭代9：全仓评审修复（2026-10-16）

依据 `docs/全仓代码评审报告.md` 第九章（第 3 轮审议）清单逐项闭环，处置记录见该报告新增的第十章。

**🔴 高危：租户/用户隔离主线闭合到末端**
- **记忆工具归属击穿**：`MemoryRetrieveTool` / `MemoryWriteTool` 的输入 schema 中**整体移除** `userId/sessionId/tenantId`——原先这三个身份字段由模型入参直传，提示注入即可读写他人 / 他租户记忆。现改为服务端投影（`UserContext` / `TenantContext`），会话维度由 `AgentToolContext` 派生（`exec:<id>` / `wf:<id>`），模型无法再伪造。
- **执行快照 / 变量越权**：`ExecutionController.getExecutionSnapshots` / `getExecutionVariables` 在 `flowId` 比对**之前**补 `getOwnedWorkflow`，越权请求不再触达快照查询。
- **知识库运行时路径无归属**：新增 `requireBaseForRuntime` 统一入口（兼容 `owner IS NULL` 的历史数据），`resolveBaseForRuntime` 的 id / name / 默认库三处查询补 `in(getOwner, visibleOwners())`，`listDocuments` / `searchRuntime` / `refreshBaseStats` 全部改走归属校验。
- **默认弱口令静默上线**：`application.yml` 默认**留空即禁用**；`ConfigValidationRunner` 在口令短于 8 位或命中弱口令字典（含 `admin123`）时抛异常拒绝启动。`.env.example` 与 `README.md` / `backend/README.md` / `AGENTS.md` 一并停止下发可用口令，并新增仓库级守卫测试。

**🟡 中危**
- **LLM 客户端零超时** → `ChatClientFactory` 注入 10s 连接 / 120s 读取超时（`SimpleClientHttpRequestFactory`），消除半开连接永久占用线程池 worker 的风险。
- **MCP 启动命令用户可控** → 新增 `McpCommandValidator`（启动器白名单 + shell 元字符拒绝 + 路径与 `.exe/.cmd/.bat/.ps1` 归一化，可用 `PAIAGENT_MCP_ALLOWED_COMMANDS` 扩展），写入侧与执行侧**双重校验**。
- **TTS / MinIO 绕过 SSRF 校验** → `SafeHttpFetcher` 抽出 `fetchBytes`：逐跳安全校验 + 声明长度与实际长度双重上限，**超限显式抛错**（二进制静默截断会产出损坏文件）；`callStepTts` 端点补 `UrlSafetyValidator`。
- **审批声明被等级分支吞掉** → `ToolGateway` 判定改为 `requiresApproval || (等级越限 && ESCALATE)`。
- **幂等非原子** → `IdempotencyGuard` 新增 `tryClaim`（进程内 `putIfAbsent` + Redis `SETNX`）与 `release`，网关执行前原子占位、失败归还，消除 check-then-act。
- **上下文不传播** → `ParallelDAGScheduler` 与 `HitlResumeService` 显式投影调用方用户 / 租户上下文，并在 `finally` 清理，防线程池复用串扰。
- **RAG 与日志** → `DenseRetriever` 把索引与元数据合并为单一 `Bucket`（`computeIfAbsent` 天然原子）；`Bm25Retriever.dfCache` 加 2 万条上限；MyBatis `log-impl` 由 `StdOutImpl` 改为 `Slf4jImpl`（SQL 与绑定参数不再直吐 stdout）。

**本轮修复过程中新发现的 3 处缺陷**
1. `IdempotencyGuard.tryClaim` 跨实例占位失效：原先「内存抢到但 Redis 已被他人持有」时仍放行，等于让 `SETNX` 对跨实例幂等形同虚设；改为 Redis 明确返回 `false` 即拒绝并撤销本地占位，仅 Redis 抛异常（不可用）时才降级为进程内判定。
2. 仓库示例配置与文档仍下发 `admin/admin123`，叠加新的 fail-fast 会让文档化的本地启动流程直接失败；已一并收口。
3. `DenseRetriever.invalidate` 前缀误伤：`invalidate(1)` 会连带清掉 `10:dimN` 的桶，随重写消除。

**⚠️ 升级注意**
- 默认管理员账户不再自动启用：需自行设置 `APP_AUTH_DEFAULT_USERNAME` / `APP_AUTH_DEFAULT_PASSWORD`（强口令，≥8 位且不在弱口令字典内），两个都留空即禁用。
- `application.yml` 此前被本地 `skip-worktree` 标记屏蔽，长期未纳入版本管理，本次一并修正。

**质量门**：全量 **491** 项测试通过（428 → +63）。

---

## v2.3.0 — 迭代8：评审报告遗留清零（2026-10-16）

**测试覆盖率补齐（消除测试盲区）**
- 对 `VolcengineAgentPlanClient` / `SearchInfinityMcpClient` 做**可测性改造**：将传输层 / 会话层提取为可注入接口，生产默认实现行为不变，从而新增纯单元测试（无需真实网络 / 子进程）。
- 新增 `VolcengineAgentPlanClientTest`（12 例）、`SearchInfinityMcpClientTest`（14 例）。
- 新增 3 个 Controller 的 MockMvc 测试：
  - `LLMConfigControllerTest`（8 例）：密钥掩码边界——掩码后密钥永不回显，携带掩码新增被拒绝。
  - `KnowledgeBaseControllerTest`（6 例）：数据归属隔离——跨用户访问返回 404。
  - `GovernanceControllerTest`（6 例）：审批人取自会话用户，伪造请求体中的审批人被忽略。

**稳定性与容量**
- HNSW 容量参数化：新增 `max-vectors`（默认 100000）；超限新向量显式抛出 `IllegalStateException`，覆盖写豁免；`HybridRetriever.safe()` 单路失败降级为词法路径；新增容量单测。
- SSRF 残余风险定性：`UrlSafetyValidator` / `SafeHttpFetcher` 拒绝 URL 内嵌凭据（`user:password@host`）；DNS-rebinding 残余经 JDK21 实证（自定义 `Host` 被丢弃、IP-pinning 不可行）定性为超出范围，代码中以注释精确记录。

**两处真实缺陷修复**
1. Volcengine 图像生成原先**静默丢弃** `negativePrompt` / `style`（另两个图像客户端会消费它们）→ 折叠进 prompt 以对齐契约。
2. `SearchInfinityMcp` **双重 `initialize()`**（openSession 已含握手+重试）+ **2xx 空响应 NPE**（空串 `parseObject` → 空 JSONObject）→ 去重握手、空体转空对象、有界单次重试（`MAX_START_ATTEMPTS=2`）。

**质量门**：全量 **426** 项测试通过。

---

## v2.2.0 — 迭代7：全仓评审修复（2026-10-16）

按全仓代码评审报告的严重程度分三批次（P0 / P1 / P2）一次性闭环：
- 数据归属隔离（跨租户 / 跨用户访问收敛）。
- SSRF 闭合（出网地址校验）。
- 审批绑定与并发（审批人取自会话、并发安全）。
- 双引擎（LangGraph4j / 自研 DAG）行为对齐。
- 死代码清理。

**质量门**：全量 **377** 项测试通过。

---

## v2.1.0 — 迭代6：修复累积缺陷（2026-10-16）

- `StateManager` 状态机修复 + LangGraph / DAG **失败语义对齐**（`fail-stop`）。
- 幂等键 SHA-256 规范化，消除碰撞。
- 租户投影（`X-Tenant-Id`）真实生效 + 执行身份注入。
- HITL 审批 **resume 闭环**：挂起快照 → 审批通过 → 断点续跑，不重放副作用。
  链路已实现并由单测覆盖（`ApprovalServiceTest` / `HitlResumeServiceTest` / `HitlSuspendContextTest`）；
  注意**默认不触发**——内置工具均为只读级别（`OBSERVE` / `DRAFT`），需在工具上声明
  `requiresApproval = true` 或把节点 `autonomyLevel` 提到 `ESCALATE`，审批分支才会进入。
- LLM 执行器 / 模板服务小缺陷修复。

**质量门**：全量 **303** 项测试通过。

---

## v2.0.0 — 二期企业级治理能力（2026-10-15）

企业级治理能力落地：
- 工具权限**分级与审计**。
- **持久化分层记忆**。
- RAG **混合检索**与离线评测。
- **有界并行 DAG**。
- **LangGraph 条件边**与 Checkpoint。

底层能力同步增强（累计）：LangGraph4j 工作流引擎、Skills 系统、TTS 音频流程、SSE 流式执行、MinIO 存储、ReAct Agent、知识库与 MCP 工具、agent-plan 调度、RAG 检索、执行快照 / 恢复、图像 / 视频客户端与媒体 API 等。

**质量门**：**243** 项测试全绿。

---

> 说明：本 CHANGELOG 与 Git 标签说明互为补充；如需在 GitHub 上生成正式的 Release 页面（带下载资产和更丰富的排版），可在安装 `gh` CLI 并登录后，由我基于以上内容逐版本创建 Release。当前环境无 `gh` 且无 GitHub Token，故采用「注解标签 + 仓库 CHANGELOG」的方式承载版本说明。
