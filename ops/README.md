# Ops Layout

这套运维脚本按职责分层，适配“一台服务器跑多个后端项目”的部署方式。

核心口径：

- `ops/server/`：服务器基础环境，只维护一份。负责 Java、Nginx、firewalld、psql、redis-cli 等共享依赖。
- `ops/apps/<app>/`：单个后端项目的运行目录、env、systemd、健康检查。每个项目只维护自己的 app 脚本。
- 不在 app 脚本里安装/重配整台服务器，避免多个项目互相覆盖主机配置。

```text
ops/server/                    # 服务器基础环境，项目无关
ops/apps/analyticshub/         # AnalyticsHub 项目运行目录、env、systemd
```

## 推荐顺序

```bash
sudo bash ops/server/init-env.sh
sudo bash ops/server/check-env.sh
sudo bash ops/apps/analyticshub/setup-app.sh
sudo vim /etc/analyticshub/analyticshub.env
sudo bash ops/apps/analyticshub/check-app.sh
```

## 线上路径约定

```text
/opt/analyticshub/app.jar             # AnalyticsHub 后端 JAR
/etc/analyticshub/analyticshub.env    # AnalyticsHub 生产配置
/var/log/analyticshub/                # AnalyticsHub 日志目录
```

Nginx 入口由服务器主配置维护。当前服务器有多个后端项目时，推荐通过路径前缀区分：

```text
/analyticshub/** -> 127.0.0.1:3001
/demo_project/**      -> 127.0.0.1:8080
```

`docs/运维/init-env.sh` 是旧的一体化脚本，适合回看历史部署方式；新部署建议使用 `ops/` 下面的分层脚本。
