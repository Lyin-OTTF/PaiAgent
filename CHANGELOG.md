# 版本说明 / Changelog

本文件汇总 PaiAgent 各发布版本的变更说明。对应 Git 标签（`v2.0.0`～`v2.3.0`）均已推送到 `origin`，可在 GitHub 的 Tags / Releases 页面查看每条标签自带的说明。

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
