---
title: 三模板演示数据
type: example-guide
status: current
audience: contributor, evaluator
scope: 本地 App、Website、WebApp 演示项目和可重复数据初始化
agent_notes: 仅操作固定 demo_* 项目；不得改成生产数据初始化脚本
---

# 三模板演示数据

该示例通过真实 Admin API 创建项目并执行项目 migration，再向三个固定的演示 schema 写入可重复生成的数据：

| 项目 | 模板 | 数据重点 |
| --- | --- | --- |
| `demo_app` | App | 设备、会话、产品事件、漏斗、留存、语义映射、Counter、隐私工单 |
| `demo_website` | Website | PV、UV、访问趋势、页面排行、来源排行、业务 Counter、隐私工单 |
| `demo_webapp` | WebApp | 产品行为和网站流量并存，并覆盖 Counter、字典和工单 |

数据时间以执行时刻为基准向前生成 60–75 天，因此默认时间范围始终可以看到有效趋势。脚本可重复执行；它只会清空并重建上述三个固定 demo schema 中的 AnalyticsHub 表，以及它们在系统库中的字典和 Dashboard 配置。

## 执行

先按照 [快速启动指南](../../QUICKSTART.md) 启动 AnalyticsHub 后端，然后在新终端加载同一份本地配置：

```bash
set -a
source ./.env.dev
set +a
bash examples/demo-data/seed.sh
```

依赖 `curl`、`jq` 和 PostgreSQL `psql`。如果本机没有 `psql`，也可以设置 `POSTGRES_CONTAINER`，通过正在运行的 PostgreSQL Docker 容器执行。后端连接 PostgreSQL 使用不同地址时，可额外设置 `PROJECT_DB_HOST`；脚本传给项目配置的地址必须从后端进程所在环境可达。

脚本结束时会打印三个项目的 Dashboard 地址。启动管理前端后，打开 `http://127.0.0.1:5173/analyticshub/`，使用与后端相同的 `ADMIN_TOKEN` 登录，即可在项目首页进入三种模板。前端启动命令也收录在 [快速启动指南](../../QUICKSTART.md#6-加载演示数据并查看完整页面可选)。

## 边界

- 这是 development/evaluation data（开发与评估数据），不是生产初始化或生产迁移。
- 所有人物、设备、域名、标识和业务数字均为虚构值。
- 脚本不会删除项目配置，也不会触碰非 `demo_app`、`demo_website`、`demo_webapp` 项目。
- 示例密码只从环境变量读取，不应写入文件或提交历史。
