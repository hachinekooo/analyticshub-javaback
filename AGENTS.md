---
title: AnalyticsHub Agent 规约
type: agent-instructions
status: current
audience: agent, contributor
scope: 项目事实、上下文入口、开发规则、安全边界、运维边界和提交规范
agent_notes: Agent 默认应先读取本文件；归档文档不作为默认上下文
---

# AGENTS.md

## Scope

This file applies to the whole `analyticshub-javaback` repository.

AnalyticsHub is an open-source Java backend for multi-project analytics operations. Treat this repository as public by default: do not add private server details, private project names, real domains, personal paths, IP addresses, tokens, passwords, SMTP credentials, or customer/business data.

## Project Facts

- Stack: JDK 25, Spring Boot 4.0.1, Spring Security 7.x, Maven, PostgreSQL 15+, Flyway, MyBatis Plus, HikariCP.
- Main package: `com.github.analyticshub`.
- Default backend port: `3001`.
- Public health endpoint: `GET /api/health`.
- Admin APIs: `/api/admin/**`, authenticated by `X-Admin-Token` or `Authorization: Bearer <token>`.
- Collection APIs: `/api/v1/**`, authenticated by API key + HMAC where required.
- Public traffic/counter APIs: `/api/public/**`, intentionally not HMAC-authenticated unless a feature-specific token is configured.
- System database stores AnalyticsHub metadata only; connected business projects use their own database/schema.

## Context Map

Read the smallest relevant context first. Do not load long docs wholesale unless the task needs that area. For documentation discovery, start with `docs/README.md`, then open only the target document section found through headings or `rg`.

Default orientation should come from this file plus the current code. Use the documentation index only when project-level or operational context is needed.

Exception: when troubleshooting the maintainer's local PostgreSQL, read `docs/本地开发/DOCKER_POSTGRES.md`.

Archived docs under `docs/归档/` are human reference material only. Do not load them by default; use them only when the user explicitly asks about historical JDK/Spring Boot migration issues.

When code, scripts, and docs disagree, inspect the code/scripts first, update the docs, and call out the mismatch in the final report.

## Development Workflow

- Read the affected controller, service, DTO, mapper/entity, config, migration, and test context before editing.
- Keep changes focused. Avoid repo-wide rewrites unless the task explicitly asks for them.
- Preserve the existing layered style:
  - controllers handle HTTP mapping, validation, request headers, and response wrapping;
  - services contain business rules;
  - mappers/entities handle persistence;
  - DTOs define API contracts.
- Use `ApiResponse` for normal API responses and `BusinessException` plus project error codes for expected failures.
- Prefer explicit DTOs, validation annotations, typed config, and allow-list validation over loose maps or stringly typed contracts.
- Use constructor injection for required Spring dependencies.

## Security Rules

- Never log or commit raw tokens, passwords, private keys, TOTP secrets, API secrets, SMTP passwords, production env files, or real server credentials.
- Admin/auth/security/2FA/rate-limit changes require targeted review and verification.
- New public endpoints must be intentional and documented.
- Do not accept admin tokens in query parameters. Existing docs and code require headers.
- Keep examples generic: use `your_project`, `analytics.example.com`, and placeholder secrets.
- Do not mention private downstream products or closed-source project internals in this open-source repository.

## Database And Migrations

- Flyway migrations live in `src/main/resources/db/migration`.
- Do not rewrite a migration that may already be released or applied to shared environments.
- For unreleased local feature work, a migration may be cleaned up before release so it describes the intended final schema clearly.
- The system database and connected project databases are separate concerns; do not make AnalyticsHub silently create external project users/databases.
- If preserving local data conflicts with clean migration history, ask the user which goal matters more.

## Ops Boundary

- `ops/analyticshub` is the unified ops entrypoint.
- This repo maintains AnalyticsHub-owned deployment scripts only:
  - host basics needed by AnalyticsHub;
  - AnalyticsHub PostgreSQL database/user/schema;
  - AnalyticsHub systemd/env/log directories;
  - `/analyticshub/` Nginx routes;
  - AnalyticsHub backup and secret rotation.
- Do not add deployment scripts for private or unrelated projects here.
- Keep real secrets server-side only, normally under root-only env/credential files such as `/etc/analyticshub/analyticshub.env`.

## Commands

Use Maven directly in this repository:

```bash
mvn -DskipTests compile
mvn test
mvn -Dtest=ClassNameTest test
mvn -DskipTests package
```

Validate ops scripts after changing `ops/**`:

```bash
bash -n ops/analyticshub
for f in ops/server/*.sh ops/apps/analyticshub/*.sh; do bash -n "$f" || exit 1; done
bash ops/analyticshub help
```

Validate YAML after editing `application.yml`:

```bash
ruby -e 'require "yaml"; YAML.load_file("src/main/resources/application.yml"); puts "yaml ok"'
```

Run the narrowest useful verification first, then broader checks when touching shared security, persistence, API contracts, or ops scripts.

## Documentation Rules

- Keep docs true to current code and scripts.
- Keep `docs/**/*.md` front matter minimal and consistent: `title`, `type`, `status`, `audience`, `scope`, `agent_notes`.
- Prefer short docs with clear links over duplicating long procedures in multiple places.
- Keep agent-facing docs dense and representative. Avoid turning index or entrypoint docs into exhaustive manuals.
- For long API docs, preserve section-level structure so agents can search and read one endpoint at a time.
- When API response examples change, match the real `ApiResponse` shape: `success`, `data`, `error`, `timestamp`.
- When Nginx or deployment paths change, update both the ops reference and the deployment guide.
- Avoid time-sensitive claims such as "latest stable" unless verified and necessary.

## Git

- Use Chinese commit messages in this format: `【模块-专题】简短说明`.
- Allowed `模块` values:
  - `文档`: documentation, README, AGENTS, guides, examples.
  - `工程`: build, dependency, repository hygiene, CI, tooling.
  - `配置`: application config, env examples, profile settings.
  - `运维`: ops scripts, deployment, Nginx, systemd, backup, rotation.
  - `安全`: auth, Admin Token, 2FA, rate limit, secret handling.
  - `数据库`: Flyway migrations, schema, SQL, persistence config.
  - `接口`: API contract, DTOs, response shape.
  - `采集`: device registration, events, sessions, traffic collection.
  - `管理`: admin project/device/event/session/metrics/counter features.
  - `隐私`: privacy request workflows.
  - `测试`: tests and test fixtures.
- Allowed `专题` values:
  - `开源口径`, `文档编排`, `脚本闭环`, `权限隔离`, `环境示例`, `发布检查`, `认证链路`, `密钥轮换`, `路由配置`, `迁移整理`, `接口契约`, `测试修复`.
- If none of the allowed values fit, ask before inventing a new module or topic.
- Example: `【文档-文档编排】统一文档头与索引口径`.
- Keep each commit focused on one coherent topic.
- Do not stage unrelated local changes.
- Do not run destructive git commands unless the user explicitly asks for them.
