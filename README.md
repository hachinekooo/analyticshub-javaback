---
title: AnalyticsHub Java Backend
type: project-readme
status: current
audience: contributor, operator, integrator
scope: 产品定位、能力边界、快速入口和文档导航
agent_notes: 入口概览；实现、API、安全和运维细节按 docs/README.md 路由
---

# AnalyticsHub Java Backend

AnalyticsHub 是一个可自行部署的多项目运营分析底座。它连接企业自己的项目数据库，提供数据采集、运营分析、语义映射、Dashboard、累计 Counter 和隐私工单闭环。

它不是公共托管 SaaS，也不包含任何下游私有项目的事件命名、业务指标、域名或 Dashboard 配置。开源仓库提供通用能力和定制扩展契约，部署方负责自己的数据、配置与私有实现。

## 核心能力

- 多项目管理：一个 Hub 管理多个项目，每个项目使用独立 database/schema。
- 数据采集：设备、事件、会话、App 流量和官网流量。
- 运营分析：基础指标、趋势、事件排行、交互式漏斗和留存。
- 业务语义：多个历史或当前 raw event key 可映射为一个稳定业务语义。
- 分析治理：统一管理属性能力、可信事件范围与数据质量，区分稳定 KPI 和跨版本诊断结果。
- 受治理指标：以稳定 `metricKey` 复用事件计数、去重统计身份（actor）、漏斗、留存、属性分布和数值摘要口径。
- Analysis Pack：通过版本化声明一次安装业务语义、属性、指标与可信 Schema 策略，不把私有业务写进开源核心。
- 项目 Dashboard：内置组件可编排，并可直接引用受治理指标；私有展示仍通过可信的 build-time extension（构建期扩展）接入。
- 累计 Counter：支持实时投影、历史回算和公开只读展示。
- 隐私工单：客服人工核验、导出或去标识化、审计和通知闭环。
- 安全与运维：Admin Token、API Key + HMAC、可选 2FA、Flyway 和统一部署脚本。

## 数据边界

AnalyticsHub 使用两类数据库：

- system database：只保存项目连接配置、语义字典和 Dashboard 定义等平台元数据。
- project database：保存该项目的设备、事件、会话、流量、Counter 和隐私工单。

管理端新增项目只登记连接信息，不会静默创建外部数据库或用户。项目库初始化和升级通过管理端项目初始化接口执行。

## 技术基线

- JDK 25、Spring Boot 4.0.7、Spring Security 7.x
- PostgreSQL 15+、Flyway、MyBatis Plus、HikariCP
- Maven；仓库统一通过 `./scripts/mvn-project` 运行
- 默认后端端口 `3001`

## 本地启动

完整步骤见 [QUICKSTART.md](QUICKSTART.md)。准备 PostgreSQL 与必要环境变量后：

```bash
./scripts/mvn-project -DskipTests compile
./scripts/mvn-project spring-boot:run
curl http://localhost:3001/api/health
```

## 接口与认证

| 范围 | 用途 | 认证 |
| --- | --- | --- |
| `/api/v1/**` | App/SDK 采集 | 按接口使用 API Key + HMAC |
| `/internal/v1/analytics/actor-links` | 业务后端登记项目内 actor alias | 项目级 service HMAC，可叠加 loopback 限制 |
| `/api/admin/**` | 管理后台 | `X-Admin-Token` 或 Bearer Token |
| `/api/public/**` | 官网流量与公开 Counter | 默认公开；功能可配置专用 Token |
| `/api/health` | 健康检查 | 公开 |

具体请求、字段和响应以 API 文档为准，不在 README 重复维护。

## 文档导航

文档的职责、读者和事实来源统一记录在 [docs/README.md](docs/README.md)。常用入口：

- [本地快速启动](QUICKSTART.md)
- [三模板演示数据](examples/demo-data/README.md)
- [架构与数据边界](docs/ARCHITECTURE.md)
- [采集端 API](docs/API_COLLECTION.md)
- [管理端 API](docs/API_MANAGEMENT.md)
- [Dashboard 与项目定制](docs/DASHBOARD_CUSTOMIZATION.md)
- [隐私工单处理](docs/PRIVACY_WORKFLOW.md)
- [生产部署](docs/运维/DEPLOYMENT_GUIDE.md)
- [版本升级索引](docs/README.md#版本本地环境与历史)

## 开发验证

```bash
./scripts/mvn-project test
```

修改运维脚本后，再运行：

```bash
bash ops/tests/run.sh
bash -n ops/analyticshub
bash ops/analyticshub help
```

## License

[MIT License](LICENSE)

## 联系与支持

- Email: hachineko@yeah.net
- GitHub: [@hachinekooo](https://github.com/hachinekooo)

如果项目对你有帮助，可以通过 [赞赏与交流方式](docs/SUPPORT.md) 支持后续维护。
