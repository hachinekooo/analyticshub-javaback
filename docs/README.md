---
title: AnalyticsHub 文档索引
type: documentation-index
status: current
audience: maintainer, agent, contributor
scope: 文档目录、阅读入口和归档边界
agent_notes: 用于选择要阅读的文档；不要替代具体 API 或运维文档
---

# 文档索引

本目录按使用场景组织文档。优先阅读与当前任务直接相关的文档，避免一次性加载长篇参考文档。

## 目录约定

- 根目录文档：当前项目通用说明、API、架构、安全和集成文档。
- `运维/`：生产部署、Nginx、systemd、备份和密钥轮换。
- `数据库操作/`：PostgreSQL 手动操作和排障参考。
- `本地开发/`：维护者本机环境说明，不代表生产口径。
- `归档/`：人工背景阅读材料，不作为当前规约或 Agent 默认上下文。

## 入门与架构

- 本地启动：[../QUICKSTART.md](../QUICKSTART.md)
- 项目总览：[../README.md](../README.md)
- 架构与认证链路：[ARCHITECTURE.md](ARCHITECTURE.md)

## API 参考

- 采集端与公开接口：[API_COLLECTION.md](API_COLLECTION.md)
- 管理端接口：[API_MANAGEMENT.md](API_MANAGEMENT.md)

API 文档是 endpoint reference（端点参考）。处理某个接口时，按路径或标题搜索对应小节，不要整篇作为默认上下文。

## 部署与运维

- 生产部署指南：[运维/DEPLOYMENT_GUIDE.md](运维/DEPLOYMENT_GUIDE.md)
- 运维脚本说明：[../ops/README.md](../ops/README.md)
- PostgreSQL 常用命令：[数据库操作/PostgreSQL常用命令.md](数据库操作/PostgreSQL常用命令.md)

## 安全与集成

- 安全配置：[SECURITY_SETUP.md](SECURITY_SETUP.md)
- 告警邮件 SMTP 配置：[EMAIL_SETUP.md](EMAIL_SETUP.md)
- 前端 2FA 对接：[FRONTEND_2FA_GUIDE.md](FRONTEND_2FA_GUIDE.md)

## 本地开发

- 维护者本机 Docker PostgreSQL：[本地开发/DOCKER_POSTGRES.md](本地开发/DOCKER_POSTGRES.md)

## 归档材料

- JDK 25 / Spring Boot 4 踩坑记录：[归档/JDK25-SpringBoot4-Guide.md](归档/JDK25-SpringBoot4-Guide.md)

归档材料面向人工排障和背景阅读，不作为当前项目规约或 Agent 默认上下文。
