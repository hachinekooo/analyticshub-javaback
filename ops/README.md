# Ops Layout

这套运维脚本按职责分层，避免一台服务器跑多个项目时互相影响。

```text
ops/server/                 # 服务器基础环境，项目无关
ops/apps/analyticshub/      # analyticshub 项目运行目录、env、systemd
```

## 推荐顺序

```bash
sudo bash ops/server/init-env.sh
sudo bash ops/server/check-env.sh
sudo bash ops/apps/analyticshub/setup-app.sh
sudo vim /etc/analyticshub/analyticshub.env
sudo bash ops/apps/analyticshub/check-app.sh
```

`docs/运维/init-env.sh` 是旧的一体化脚本，适合回看历史部署方式；新部署建议使用 `ops/` 下面的分层脚本。
