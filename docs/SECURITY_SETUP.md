# 🔒 AnalyticsHub 安全配置指南

本文档指导如何安全地配置和管理 AnalyticsHub 项目的敏感信息。

## 📋 敏感信息保护

### 1. 环境变量文件
项目使用环境变量来管理敏感信息，请勿将以下文件提交到版本控制：

- `.env` - 本地环境配置（包含真实密码）
- `.env.dev` - 开发环境配置（可选）
- `.env.prod` - 生产环境配置（可选）
- `secrets.properties` - 密钥文件

### 2. 提供的模板文件
以下模板文件已添加到项目中：

- `.env.example` - 环境变量配置示例
- `.env.dev.example` - 开发环境配置示例
- `.gitignore` - 已配置忽略敏感文件

## 🚀 快速开始

### 本地开发环境设置

1. **复制环境模板**：
   ```bash
   cp .env.dev.example .env
   ```

2. **修改配置**（如果需要）：
   ```bash
   # 编辑 .env 文件，修改数据库密码等敏感信息
   vim .env
   ```

3. **启动应用**：
   ```bash
   # Spring Boot 默认不会自动读取 .env，需要先导入到当前 shell
   set -a
   source .env
   set +a
   
   mvn spring-boot:run
   ```

### 生产环境部署

1. **设置环境变量**：
   ```bash
   # 在服务器上设置环境变量
   export DB_PASSWORD="your_secure_production_password"
   export ADMIN_TOKEN="$(openssl rand -hex 32)"
   export SPRING_PROFILES_ACTIVE=prod
   ```

2. **或者创建 .env 文件**：
   ```bash
   # 创建生产环境配置文件
   cat > .env << EOF
   DB_HOST=localhost
   DB_PORT=5432
   DB_NAME=analytics
   DB_USER=analytic
   DB_PASSWORD=generated_secure_password_123
   ADMIN_TOKEN=$(openssl rand -hex 32)
   SPRING_PROFILES_ACTIVE=prod
   EOF
   ```

## 🔧 配置说明

### 数据库配置
应用现在支持通过环境变量配置数据库：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:analytics}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root}
```

### 管理令牌配置
管理端令牌通过环境变量配置：

```yaml
app:
  security:
    admin-token: ${ADMIN_TOKEN:}
```

管理端 Token 用途：
- 生产环境访问 `/actuator/**` 时需要携带 `X-Admin-Token` 或 `Authorization: Bearer <token>`
- 访问管理端项目接口 `/api/admin/**` 需要携带 `X-Admin-Token` 或 `Authorization: Bearer <token>`
- 校验接口：`POST /api/v1/auth/admin-token/verify`

## 🛡️ 安全最佳实践

### 1. 密码生成
使用强密码生成命令：
```bash
# 生成随机数据库密码（64 位）
openssl rand -hex 32

# 生成管理令牌（64 位）
openssl rand -hex 32
```

### 2. 文件权限
确保敏感文件权限正确：
```bash
chmod 600 .env .env.*
chown root:root .env .env.*
```

## 📝 检查清单

- [ ] 已创建 `.env` 文件并配置敏感信息
- [ ] `.env` 文件已添加到 `.gitignore`
- [ ] 生产环境使用强密码和令牌
- [ ] 文件权限设置正确
- [ ] 定期轮换密钥和密码

## 🚨 紧急情况

如果敏感信息意外提交：

1. 立即轮换所有密码和令牌
2. 从 git 历史中清除敏感文件：
   ```bash
   git filter-branch --force --index-filter \
     "git rm --cached --ignore-unmatch .env .env.*" \
     --prune-empty --tag-name-filter cat -- --all
   ```

3. 强制推送到远程仓库：
   ```bash
   git push origin --force --all
   git push origin --force --tags
   ```

## 📞 支持

如有安全问题，请立即联系项目维护者。
