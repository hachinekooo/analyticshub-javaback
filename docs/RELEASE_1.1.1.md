---
title: AnalyticsHub 1.1.1 升级说明
type: release-guide
status: current
audience: maintainer, operator
scope: 1.1.0 到 1.1.1 的事件旅程、项目迁移、历史元数据回填和发布顺序
agent_notes: 发布或升级 1.1.1 前阅读；真实项目名、地址和凭据不得写入本文
---

# AnalyticsHub 1.1.1 升级说明

1.1.1 在既有 actor identity（统计身份）基础上增加以真实事件为锚点的用户旅程查看，并完善事件身份筛选和属性按需加载。管理端前端 `1.1.1` 与本版本配套部署。

## 兼容边界

- 现有事件采集、设备认证和 1.1.0 管理接口保持兼容；已发布客户端无需等待本版本才能继续上报。
- 新旅程接口依赖 Project Schema V8。后端部署后应在低流量时段逐个初始化项目，不要让新 JAR 长期运行在 V7。
- Inks Backend `1.1.0` 无本轮配套改动，不需要再次升级。
- 1.1.1 的项目迁移是 forward-only（只向前）；升级前仍应备份 system database 和全部 Project database。

## 数据库迁移

system database 不变。本轮每个 project database 从 V7 升级到 V8：

- 为 `events` 增加 `properties_size_bytes` 和 `identity_scope` 两个可空元数据列；
- 增加普通事件筛选、归一身份旅程和历史元数据回填所需的三个索引；
- 不新增业务表，也不改写事件正文；
- 新事件在写入时同步保存属性逻辑大小与身份范围；
- 旧事件由后台以小批次补齐元数据，任务可续跑，不阻塞服务启动。

后端识别事件表的真实列能力：Project 仍为 V7 时继续使用原写入字段，事件采集不中断；完成 V8 后自动切换到元数据写入。后台回填同样只在 V8 列就绪后运行，不会对尚未初始化的项目重复报缺列错误。

逐个 Project 调用初始化后，确认 `schemaVersion=8`、`pendingMigrations=0`、`migrationHistoryValid=true`。当前小规模、低流量部署采用一次事务完成 V8 结构升级；若未来事件表显著增大，应在升级前重新评估维护窗口。

## 推荐发布顺序

1. 备份 AnalyticsHub system database 和全部 Project database。
2. 部署 AnalyticsHub backend `1.1.1`。
3. 在低流量时段逐个初始化 Project，确认 V8 状态和后台回填日志无持续错误。
4. 验证旧客户端仍可上报，再部署配套 frontend `1.1.1`。
5. 在事件记录中选择一条事件，确认旅程按发生时间排列，登录前后身份可归并，较大属性可按需加载。

后端必须先于前端升级；iOS 可以晚于 AnalyticsHub 发布。后端提前上线只会继续接收旧事件，不会要求旧客户端发送新字段。

## 最小验收

- `/api/health` 返回版本 `1.1.1`；
- Project Schema 为 V8，迁移历史有效；
- 旧客户端事件采集与现有 Dashboard 查询正常；
- 事件列表原有分页和筛选行为不变；
- 使用 raw anonymous actor 或 canonical cloud actor 查询时，都能得到同一归一身份的连续旅程；
- 同毫秒事件按数据库写入顺序稳定显示，锚点不会因 200 条限制丢失；
- 默认展开可安全展示的属性，超出在线预算的属性只允许按规则加载；
- 历史事件元数据回填完成后，`properties_size_bytes` 不再持续存在待处理空值。

## 回滚边界

不要修改已应用的 V8 checksum。V8 仅增加可空列和索引，旧 `1.1.0` JAR 不会使用这些字段；如需临时回退应用，可保留 V8 Schema，但旅程与历史回填能力将不可用。不要为了回退删除列、索引或历史事件。
