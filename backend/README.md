# PaiAgent Backend

AI Agent 流图执行面板后端服务

## 技术栈

- Java 21
- Spring Boot 3.4.1
- Spring AI 1.0.0-M5
- Spring AI Alibaba 1.0.0.2
- LangGraph4j Core 1.1.5 + Spring AI 1.8.0-beta3
- MyBatis-Plus 3.5.5
- MySQL 8.0
- Maven 3.8+

## 快速开始

### 1. 数据库初始化

确保 MySQL 已安装并启动,然后执行以下命令创建数据库和表:

```bash
mysql -u root -p < src/main/resources/schema.sql
```

或手动执行 `schema.sql` 中的 SQL 语句。

### 2. 配置数据库连接

本项目使用环境变量管理敏感配置。

复制环境变量模板并编辑：

```bash
cp .env.example .env
```

编辑 `backend/.env` 文件，设置你的数据库密码：

```bash
# 必填：数据库密码
MYSQL_PASSWORD=your_mysql_password

# JWT 密钥（生产环境必须修改！）
JWT_SECRET=your_jwt_secret_key_minimum_32_characters

# 默认管理员账户（可选；两个都留空 = 禁用默认账户）
# ⚠️ 弱口令（admin / admin123 / 123456 等）或短于 8 位的口令会导致启动失败
APP_AUTH_DEFAULT_USERNAME=admin
APP_AUTH_DEFAULT_PASSWORD=your_own_strong_password
```

或通过系统环境变量设置：
```bash
export MYSQL_PASSWORD=your_password
export JWT_SECRET=your_jwt_secret
```

> **安全提示**：`.env` 文件已被加入 `.gitignore`，不会提交到版本库。

### 3. 运行项目

```bash
./mvnw spring-boot:run
```

`.env` 会在启动时自动导入；如果你是从仓库根目录运行，也会自动读取 `backend/.env`。

或使用 IDE 运行 `PaiAgentApplication.java`

已内置 `spring-boot-devtools`，开发时修改代码并触发编译后，应用会自动热重启。

如果你用 IntelliJ IDEA，建议同时打开：

- `Build project automatically`
- `Advanced Settings > Allow auto-make to start even if developed application is currently running`

### 4. 访问 API 文档

启动成功后,访问: http://localhost:8084/swagger-ui.html

## 默认账户配置

当前版本支持通过环境变量配置默认管理员账户（用于开发测试）：

```bash
APP_AUTH_DEFAULT_USERNAME=admin
APP_AUTH_DEFAULT_PASSWORD=your_own_strong_password
```

> 仓库不再内置任何可用默认口令：两个变量都留空即禁用默认账户；
> 若显式配置了弱口令（`admin` / `admin123` / `123456` / `password` 等）或短于 8 位的口令，
> 应用会在启动时直接失败（`ConfigValidationRunner` fail-fast），避免弱口令被静默带上线。

**生产环境建议**：
- 禁用默认账户：将两个变量都设为空值
- 或添加用户注册/管理功能（待实现）
- 使用强密码（至少 8 位，包含大小写字母和数字）

> ⚠️ **安全警告**：默认账户仅用于开发环境，生产环境必须修改或禁用！

## API 接口

### 认证接口

- POST /api/auth/login - 用户登录
- POST /api/auth/logout - 用户登出
- GET /api/auth/current - 获取当前用户信息

### Skills 接口

- GET /api/skills - 获取所有技能摘要
- GET /api/skills/{name} - 获取技能详情
- GET /api/skills/{name}/references/{ref} - 获取技能引用文档

## 项目结构

```
src/main/java/com/paiagent/
├── common/           # 通用类
├── config/           # 配置类
├── controller/       # 控制器
├── dto/              # 数据传输对象
├── engine/           # 核心引擎
│   ├── dag/          # DAG 工作流引擎
│   ├── langgraph/    # LangGraph4j 状态图引擎
│   ├── skill/        # Skills 技能系统
│   ├── llm/          # LLM 调用层
│   ├── executor/     # 节点执行器
│   └── model/        # 数据模型
├── entity/           # 实体类
├── interceptor/      # 拦截器
├── mapper/           # MyBatis Mapper
└── service/          # 服务层
```
