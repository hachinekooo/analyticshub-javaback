---
title: AnalyticsHub 1.1.2 升级说明
type: release-guide
status: current
audience: maintainer, operator
scope: 1.1.1 到 1.1.2 的属性与指标治理、Analysis Pack、数据质量、查询预算和数据库迁移
agent_notes: 发布或升级 1.1.2 前阅读；真实项目名、地址和凭据不得写入本文
---

# AnalyticsHub 1.1.2 升级说明

1.1.2 在 1.1.1 事件旅程与 Project Schema V8 基线上，增加项目级属性语义、受治理指标、Analysis Pack、数据质量检查和交互式查询预算。管理端前端 `1.1.2` 与本版本配套部署。

## 版本与兼容边界

- `V8__add_event_journey_support.sql` 属于已发布的 1.1.1，生产已应用后不得修改文件或 checksum。
- 1.1.2 新增 system V7 和 Project V9；它们在本版本发布前可以随工作区收口，一旦应用到共享或生产数据库就转为不可改写的 forward-only migration（只向前迁移）。
- 旧 Dashboard 定义保持可读；管理端当次查询未传 `propertyFilters` 时，原始分析 API 继续使用“不附加属性筛选”的全量统计语义，但仍受 1.1.2 公布的时间范围、候选数和超时预算保护。首次启用属性治理时，后端会原子预检活动指标以及 Dashboard 的分组/旅程引用，不会留下部分迁移状态。
- 事件采集增加统一属性预算：32 KiB、128 个递归键、6 层嵌套、128 项数组、4096 字符字符串和 100 字符键。发布前必须回放当前受控客户端 payload；本项目不为未经证明的第三方超大 payload 维护长期双模式。
- 批量采集仍为 HTTP 201 + best-effort（逐项尽力处理），但响应 `data` 新增 `received / accepted / inserted / duplicate / rejected` 摘要和有界错误代码计数。受控 SDK 应在后续迭代中读取 `rejectedCount`，不应只把 201 解释为整批落库。
- 公开流量采集的同源 Referer 由 `TRAFFIC_SAME_ORIGIN_HOSTS` 显式声明；不再信任调用方可伪造的请求 Host。未声明或外部来源只保留 Host，避免误存外部路径。

## System Database V7

system database 从 V6 升级到 V7，新增：

- `analytics_property_definitions`：声明顶层事件属性的数据类型、展示名称、允许值，以及是否允许筛选、分组或旅程关联；
- `analytics_metric_definitions`：以稳定 `metricKey` 保存事件计数、唯一 actor、漏斗和留存声明式规则；
- `analytics_analysis_packs` 与审计表：保存版本化项目配置、Manifest checksum 和每次导入事实。

Analysis Pack 可声明通用 `trustedSchemaPolicy`。活动指标必须筛选可信值，或显式记录已验证的跨版本口径；Pack 作为完整快照，停用被省略定义或移除可信策略前需要管理端二次确认。服务端会在 `error.details` 中返回将停用的属性、指标和可信策略，管理端展示准确清单后才发起确认请求。管理端可重新载入现有 Pack 的完整服务端快照，以更高版本执行升级或恢复。

可信策略引用 Pack 外部属性时，后续单项编辑仍会在同一事务内校验策略约束；破坏 active、非敏感、可筛选 STRING 或可信值集合的修改会回滚，避免留下“策略存在但已不可执行”的配置。

语义 Key 的停用、删除与 alias 替换会同时检查 active metric 和 active Dashboard 的漏斗/留存引用；存在依赖时返回 `SEMANTIC_DEFINITION_IN_USE`，不会让已保存看板静默清零或改变口径。

这些表只保存项目分析配置，不复制、清理或改写 Project Database 中的原始事件。Analysis Pack 不接受 SQL、脚本、HTML、远程 URL 或动态 import；同项目导入与单项定义写入串行执行，不同 Pack 不能重复声明同一 Key。

## Project Database V9

Project Database 从已发布的 V8 升级到 V9。V9 只增加 `(project_id, event_type, event_timestamp, id)` 复合索引，匹配漏斗、留存和受治理指标的核心扫描顺序；不新增业务表，不修改 V8 列，不改写原始事件。

当前数据规模较小，V9 使用普通原子 Flyway migration。应在低流量时段执行；未来事件表显著增大时，再根据真实锁等待和 `EXPLAIN (ANALYZE, BUFFERS)` 评估后续索引策略，不回改已发布迁移。

## 查询和数据质量边界

- 交互式分析默认限制为 180 天、200,000 条事件/会话候选和 15 秒事务超时；漏斗另限制 1,000 个分组以及 256 字符的分组/旅程维度值。超限明确失败，不将截断结果伪装成完整统计。
- 数据质量检查另设 50,000 条事件预算，展示项目声明的可信协议值、异常时间、超大属性、类型漂移和允许值越界；可信协议值分布超过 200 种时只截断展示清单并明确告警，异常事件总数仍按完整范围计算，不会让整份报告失败。项目特有环境维度通过普通受治理属性声明。
- 数据质量响应用 `trustedSchemaPolicyConfigured` 明确区分“已验证且无已知问题”和“尚未建立可信协议”；不能仅凭空 `issues` 判断 clean。
- 留存按成熟 cohort（已满足观察天数的分组）计算分母，且只有严格晚于首次 cohort event 的 return event 才计为回访。
- 漏斗与留存使用 `(event_timestamp, id)` 判定同毫秒事件先后，不依赖无稳定顺序的时间戳单键。

## 推荐发布顺序

1. 确认当前后端为 1.1.1、system database 为 V6、所有 Project Database 为 V8，且 V8 migration history 有效。
2. 备份 AnalyticsHub system database 和全部 Project Database。
3. 部署 backend `1.1.2`，确认 system database 升级到 V7。
4. 如使用公开官网流量采集，在服务 env 中配置 `TRAFFIC_SAME_ORIGIN_HOSTS`；不使用则保持为空。
5. 在低流量时段逐个执行 Project 初始化，确认 `schemaVersion=9`、`pendingMigrations=0`、`migrationHistoryValid=true`。
6. 回放当前客户端的单条与批量 payload，确认预算内事件正常写入，且批量摘要 `rejectedCount=0`。
7. 部署 frontend `1.1.2`，在“分析配置”中登记通用属性、导入一个不含私有产品标识的示例 Analysis Pack，并运行数据质量检查。

Backend 必须先于 frontend 升级。本版本不要求其他业务后端同步升级；采集 SDK 可后续补充对批量拒绝摘要的显式处理。

## 最小验收

- `/api/health` 返回版本 `1.1.2`；
- system schema 为 V7，Project Schema 为 V9，迁移历史有效；
- V8 checksum 与 1.1.1 生产基线一致；
- 未传 `propertyFilters` 的原始概览、App 版本分布、趋势、排行、漏斗和留存不附加属性筛选；所有请求仍受公开预算保护，超限明确失败且不返回部分统计。设备库存字段更名为 `devicesInventoryTotal`，明确不属于事件属性分群；
- 预算内事件正常写入并触发派生计数；超预算单条返回 400/413，批量摘要明确返回拒绝数；
- 使用 raw anonymous actor 或 canonical cloud actor 查询时，仍能得到同一归一身份的连续旅程；
- 属性筛选同时作用于概览、趋势、排行、漏斗与留存，且只允许项目字典声明的 key/operator/value；
- 启用中 Pack 管理的属性与指标不能被单项 API 绕过修改，导入版本、checksum 和审计事实一致；
- Pack 省略既有定义时，未显式确认不会停用任何配置；
- 稳定 KPI 通过 Analysis Pack / `metricKey` 显式筛选项目 `trustedSchemaPolicy` 声明的属性和值；历史版本仍保留用于趋势、兼容与事故复盘；
- 配套 1.1.2 管理前端会把 `trustedSchemaPolicy` 作为六类事件分析的默认范围；管理员移除该筛选时，页面明确标识为跨版本诊断数据，原始事件明细不受影响；
- 仍被 active metric 引用的 semantic key 不能被停用、删除或替换原始事件映射；
- 数据质量页不会在类型漂移、允许值越界或覆盖截断时误报 clean。

## 回滚边界

已经应用的 V8 始终不得修改。system V7 或 Project V9 一旦应用后也不得改写 checksum；旧 `1.1.1` JAR 会忽略新增的 system 配置表和 Project 索引。如需临时回退应用，应停止新的分析配置写入并保留已升级 Schema；不要删除列、索引、配置审计或原始事件。
