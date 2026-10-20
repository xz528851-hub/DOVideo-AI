# 上游面试文档 vs 当前代码：逐条核对

## 这份文件是什么

仓库作者公开了一份《面向简历的全方位解析》(约 5.4 万字) 的面试拷打文档，流传很广。
本文件把它和**当前代码**逐条核对，标出哪些内容可以直接用、哪些是通用八股、哪些与代码不符。

核对方式：以仓库当前 `main` 分支的代码与配置文件为准，必要时用 `git log` 追查改动历史。

## 结论概览

该文档不是代码说明书，而是一份跨越约 8 个月的笔记，内容混杂三类：

| 类型 | 占比 | 处理方式 |
| :--- | :--- | :--- |
| 面试方法论、AI Coding 工作流、产品思维 | 约 40% | 可直接用，与代码无冲突 |
| 通用八股（RocketMQ/Kafka 对比、Watchdog 原理、Lua 原子性等） | 约 30% | 内容正确，但**不能声称是本项目实现** |
| 具体实现细节、表结构、Key 名、配置项、性能数字 | 约 30% | **必须以代码为准**，下面逐条列出 |

作者本人在文档里已主动标注了三处"已遗弃"：Function Calling、轮询、MinIO 对象存储。

## 一、异步调度（RocketMQ）

| 文档说法 | 代码事实 | 证据 |
| :--- | :--- | :--- |
| 消息体含「任务ID、视频MD5、MinIO存储路径」 | 只有 `mediaId/action/contentHash/userGoal/mode`，从无路径字段 | `dto/AnalysisTaskMsg.java` |
| 前端 3 秒轮询 | 当前用 SSE；轮询是第一代方案 | `controller/AnalysisController.java` 的 `/analysis-events`，引入于 2026-07 (`14e2533`) |
| 锁 Key = `lock:video:{MD5}`，抢不到即丢弃 | 任务锁 `lock:analysis:media-{id}:{目标摘要}` 用 `tryLock`；另有内容锁用 `lockInterruptibly` 排队等待后复用结果 | `consumer/VideoAnalysisConsumer.java:108,117`；`utils/AnalysisTaskKeys.java` |
| `Max Reconsume Times = 3` | 注解值是 `maxReconsumeTimes = 2`（2 次重投 = 3 次投递） | `VideoAnalysisConsumer.java:41,46` |
| Broker 同步刷盘 + 主从同步复制 | 一直是 `ASYNC_FLUSH` + `ASYNC_MASTER`，单机无 slave | `rocketmq/broker.conf`（git 历史中未变过） |
| 死信队列 + 死信监听器 + 告警 + 转存容灾日志 | 自定义主题 `video-analysis-dead-topic` + MySQL 失败台账 + 人工重投接口；无监听器/告警/容灾日志 | `VideoAnalysisConsumer.java:211`；`controller/AdminController.java` |
| 「订单」「顺序消费」两节 | 未配置 `consumeMode = ORDERLY`，也无顺序依赖需求 | `VideoAnalysisConsumer.java` 注解 |

**代码里真正值得讲、文档没讲透的三点**：

1. 幂等键"先占坑 + 失败回滚"：`AnalysisDispatchService.java:72` 占坑，`catch` 里必须删掉，否则用户 6 小时内重试全被判重复。
2. 永久失败 vs 临时失败：`isPermanentFailure()` 沿 cause 链找 `IllegalArgumentException/SecurityException/NoSuchElementException`，永久失败直接收敛，不浪费重试。
3. 毒消息确认的硬条件：台账与死信主题**都**写失败时主动抛异常拒绝 ACK，避免消息静默丢失 (`VideoAnalysisConsumer.java:285`)。

## 二、分片上传与断点续传

| 文档说法 | 代码事实 | 证据 |
| :--- | :--- | :--- |
| 前端计算文件 MD5 作为 identifier | **前端没有任何 MD5 代码**；`uploadId` 是服务端生成的 UUID | `client/src/chunkUpload.js`；`ChunkUploadService.initialize()` |
| Redis Key = `upload:task:{file_md5}` | 实际是 `upload:chunked:{uploadId}` (Hash) + `:parts` (Set) | `ChunkUploadService.java:37` |
| 有 `/check` 接口 | 实际是 `GET /media/upload-status` | `controller/MediaController.java:48` |
| 每个分片后端正算 MD5 并比对 | `upload-chunk` 无分片 MD5 参数，服务端不校验分片完整性 | `MediaController.java:55` |
| 调用 MinIO `composeObject` 合并 | `MinioUtils` 中无 `composeObject`；实际是逐片读回、本地拼接、整体上传，边拼边算 MD5 | `MinioUtils.java`；`ChunkUploadService.complete()` |
| 合并后与前端传来的全局 MD5 比对 | 无比对；算出的 MD5 直接写入 `content_hash` | `ChunkUploadService.complete()` |
| 「5MB 是 MinIO 硬性规定」 | 本实现未使用 MinIO 分片上传，该理由不成立 | 同上 |
| 「双写 MySQL 分片记录做备份」 | 数据库无任何分片表 | `db/migration/V1__create_core_tables.sql` |
| MinIO 生命周期规则自动清理分片 | 代码里**显式删除**：合并成功后逐片 `removeObject` | `ChunkUploadService.cleanup()` |
| Redis Set 记录分片、TTL 24 小时 | 一致（`opsForSet` + 1 天过期） | `ChunkUploadService.java` |
| 前端统一触发合并 + 后端加锁幂等 | 逻辑一致，但锁是 Redisson `RLock` (`lock:upload:merge:{uploadId}`)，不是 SETNX | `ChunkUploadService.complete()` |

## 三、限流 / 会话记忆 / 对象存储

| 文档说法 | 代码事实 | 证据 |
| :--- | :--- | :--- |
| 自写 Lua + Redis Hash 存桶状态 (rate 10/秒, capacity 60) | 实际是 Redisson `RRateLimiter`：用户 5 次/分 + 全局 30 次/分；无 Hash、无 `last_time/tokens` 字段 | `AnalysisDispatchService.java:29,30,131` |
| 被限流返回 429 | 一致 | `common/ErrorCode.java:22` |
| 追问记忆 Key = `chat_history:{userId}:{videoId}`，String，24 小时 | 实际是 `followup:history:` + (mediaId, 目标摘要, 模式)，**List**，TTL **7 天**，`trim(-10,-1)` 保留 10 条 | `AiService.java:44,46,48,435,447` |
| 「下一步计划引入 RAG」，当前是 MySQL LIKE 检索 | 代码已有 Qdrant 向量库 + Embedding + 关键词混合检索 | `VideoEvidenceRetrievalService.java` |
| 「Function Calling 是编的，已遗弃」 | 实际已实现：`chatWithTools` 用 LangChain4j ToolSpecification，并校验工具白名单 | `DeepSeekUtils.java:468-503` |
| 预签名 URL 有效期 5 分钟 | 实际 1 小时 (`expiry(60 * 60)`) | `MinioUtils.java:151` |
| 原视频 24 小时后自动物理删除 | 无自动删除；仅用户主动删除媒体时移除对象 | `MediaService.deleteOwnedMedia()` |

## 四、数据库（差异最大的一节）

| 文档说法 | 代码事实 |
| :--- | :--- |
| 4 张表：`user_info` / `video_task_record` / `video_transcription` / `ai_summary_result` | 实际：`users` / `media_files` / `agent_checkpoints` / `failed_analysis_tasks`，**表名无一相符** |
| 每张表标配 `is_deleted` 逻辑删除 | schema 中不存在该字段 |
| `video_md5` 建**唯一索引**，靠 `DuplicateKeyException` 物理兜底 | `content_hash` 是**普通索引** `idx_media_content_hash`，非 UNIQUE |
| 状态字段用 `TINYINT` | 实际 `VARCHAR(32)` |
| 逐字稿与摘要**垂直拆分**到独立明细表 | 相反：`transcript_text`、`ai_summary` 均为 `media_files` 主表中的 LONGTEXT 字段 |
| `(user_id, create_time)`、`(status, create_time)` 联合索引 | 概念一致，实际字段名为 `upload_time` |

## 使用建议

1. 面试话术以**当前代码**为基线；文档只用来提供思考框架和八股素材。
2. 讲通用八股时明确区分："通用方案是 X，我项目里实际用的是 Y"。
3. 性能数字（60s→50ms、1000 倍）需自行实测后才能引用；仓库中无任何压测产物。
4. 本文档可作为二次开发的差异清单，用于撰写自己的 README。
