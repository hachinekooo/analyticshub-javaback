---
title: AnalyticsHub 文档索引
type: documentation-index
status: current
audience: maintainer, agent, contributor, operator
scope: 文档职责、阅读路径、事实来源和归档边界
agent_notes: 先按任务选择目标文档；索引不替代具体文档
---

# AnalyticsHub 文档索引

文档按问题类型分工。入口文档保持简短，参考文档允许较长，但必须便于按标题或 API 路径检索。一个事实只选择一个主要维护位置，其他文档只给摘要和链接。

## 从哪里开始

| 你的问题 | 首选文档 |
| --- | --- |
| 这个项目解决什么问题 | [项目 README](../README.md) |
| 如何从零启动并看到完整页面 | [快速启动](../QUICKSTART.md) |
| 如何加载三种模板的演示数据 | [三模板演示数据](../examples/demo-data/README.md) |
| 系统库、项目库和认证如何协作 | [架构与认证链路](ARCHITECTURE.md) |
| 如何新建项目、初始化 Schema 和选择分析模板 | [管理端 API：项目创建、初始化与模板选择](API_MANAGEMENT.md#项目创建初始化与模板选择) |
| App/官网怎样采集数据 | [采集端 API](API_COLLECTION.md) |
| 管理后台怎样调用接口 | [管理端 API](API_MANAGEMENT.md) |
| 怎样处理隐私工单 | [隐私工单处理流程](PRIVACY_WORKFLOW.md) |
| 怎样做项目专属 Dashboard | [Dashboard 与项目定制](DASHBOARD_CUSTOMIZATION.md) |
| 怎样部署到生产服务器 | [生产部署指南](运维/DEPLOYMENT_GUIDE.md) |
| 如何从 1.0.0 升级到 1.0.1 | [1.0.1 升级说明](RELEASE_1.0.1.md) |
| 如何从 1.0.1 升级到 1.1.0 | [1.1.0 升级说明](RELEASE_1.1.0.md) |

## 文档职责

### 入口与设计

- [项目 README](../README.md)：产品定位、开源边界、能力概览和导航；不保存完整配置或部署步骤。
- [快速启动](../QUICKSTART.md)：本地数据库、后端启动、演示数据和前端验收最短路径；不覆盖生产部署。
- [架构与认证链路](ARCHITECTURE.md)：模块关系、认证时序和数据隔离；不重复所有接口字段。
- [Dashboard 与项目定制](DASHBOARD_CUSTOMIZATION.md)：声明式布局与可信源码扩展契约；不包含私有项目配置。

### 接口与业务流程

- [采集端 API](API_COLLECTION.md)：`/api/v1/**` 和 `/api/public/**` 的 endpoint reference（端点参考）。
- [管理端 API](API_MANAGEMENT.md)：`/api/admin/**` 的 endpoint reference。
- [隐私工单处理流程](PRIVACY_WORKFLOW.md)：状态机、客服操作、导出/去标识化和审计口径；HTTP 字段仍以 API 文档为准。

API 文档较长是因为它们承担完整接口参考职责。处理单个接口时，应按 URL 或小节检索，不要把整篇当作入门材料。

### 安全、部署与运行

- [安全配置](SECURITY_SETUP.md)：敏感信息边界、认证原则和密钥轮换入口。
- [邮件与工单通知](EMAIL_SETUP.md)：SMTP 参数、安全告警和工单 outbox 验证，不负责完整部署。
- [前端 2FA 对接](FRONTEND_2FA_GUIDE.md)：管理端 2FA challenge 与请求重试契约。
- [生产部署指南](运维/DEPLOYMENT_GUIDE.md)：面向服务器管理员的端到端部署顺序。
- [运维脚本](../ops/README.md)：`ops/analyticshub` 命令、参数和脚本维护边界。
- [PostgreSQL 常用命令](数据库操作/PostgreSQL常用命令.md)：人工排障参考，不替代部署脚本。

### 版本、本地环境与历史

- [1.0.1 升级说明](RELEASE_1.0.1.md)：只服务 1.0.0 → 1.0.1 升级和回滚判断。
- [1.1.0 升级说明](RELEASE_1.1.0.md)：说明 actor identity、活跃版本指标、项目 V7 迁移和服务凭据配置。
- [维护者 Docker PostgreSQL](本地开发/DOCKER_POSTGRES.md)：维护者本机事实，不代表通用或生产配置。
- [JDK 25 / Spring Boot 4 迁移记录](归档/JDK25-SpringBoot4-Guide.md)：历史排障材料，不作为当前事实来源。
- [联系与支持](SUPPORT.md)：社区联系和赞赏，不属于技术文档入口。

## 事实来源优先级

文档与实现不一致时，按以下顺序核对并修正文档：

1. Controller、Service、DTO、migration 和当前配置代码；
2. `ops/analyticshub` 及其脚本；
3. API/运维/业务专题文档；
4. 根 README 和其他摘要；
5. `归档/` 内容。

已发布 migration 不因文档表述而改写。涉及真实生产环境时，还应以服务器当前配置、数据库备份和已部署 artifact 为准。

## 目录约定

- `运维/`：生产部署流程。
- `数据库操作/`：人工数据库命令与排障。
- `本地开发/`：维护者本机说明。
- `归档/`：非当前口径的历史材料。

新增文档前先判断是否可以补入已有职责明确的专题。不要为了减少单文件行数，把一个连续流程机械拆成多份难以导航的小文档。
