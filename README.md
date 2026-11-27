# Rememberme

Rememberme 是一个 Java 17 和 Spring Boot 会话记忆服务。它将会话消息持久化到 MySQL，用 Redis 缓存短期上下文，并可选用 MySQL、MemOS 或 Mem0 保存长期记忆。

## 本地启动

在本机安装 JDK 17 和 Maven，使用 Docker Compose 启动 MySQL 和 Redis。从仓库根目录启动。

```sh
docker compose up -d --wait mysql redis
mvn spring-boot:run
```

默认地址为 `http://127.0.0.1:8080`。本机应用连接 `127.0.0.1:13306` 的 MySQL 和 `127.0.0.1:16379` 的 Redis。Flyway 在应用启动时执行 `resources/db/migration/` 下的数据库迁移。

## 用 Compose 启动整个服务

```sh
docker compose --profile app up -d --build --wait
```

Compose 会构建应用镜像，并等待 MySQL 和 Redis 健康后启动应用。应用默认映射到本机 `8080` 端口，可用 `APP_HOST_PORT` 调整。Compose 读取当前终端的环境变量和仓库根目录的 `.env`。直接运行 `mvn spring-boot:run` 时，请把变量设在启动 Maven 的终端环境中。

## 配置

| 使用场景 | 变量 |
| --- | --- |
| 本地数据源 | `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`。通过 Compose 运行应用时，MySQL 和 Redis 的地址由服务名配置。 |
| API 鉴权 | `MEMORY_API_TOKEN`。设置后，`/api/v1/` 请求需携带 `Authorization: Bearer <token>`。默认留空。 |
| 模型生成 | `MEMORY_LLM_MODEL`、`MEMORY_LLM_BASE_URL`、`MEMORY_LLM_API_KEY`。服务调用兼容 OpenAI Chat Completions 的接口。模型地址默认是本机的 `http://localhost:11434/v1`，容器内的默认地址使用 `host.docker.internal`。 |
| 长期记忆 | `MEMORY_LONG_ENABLED` 默认 `false`。启用后用 `MEMORY_LONG_PROVIDER` 选择 `MYSQL`、`MEMOS` 或 `MEM0`，默认选择 `MEMOS`。 |
| 后台写入 | `MEMORY_LONG_IDLE_TIME` 默认 `10m`。`MEMORY_LONG_BATCH_TURNS` 默认 `10` 轮。 |
| MemOS | `MEMORY_MEMOS_BASE_URL`、`MEMORY_MEMOS_OWNER_ID`、`MEMORY_MEMOS_AUTHORIZATION`。填写已注册的 owner ID，按 MemOS 服务配置提供完整的 `Authorization` 请求头值。 |
| Mem0 | `MEMORY_MEM0_API_KEY`。选择 `MEM0` 时提供。 |

## HTTP API

路由以 `/api/v1/tenants/{tenant}/users/{user}/agents/{agent}` 开头。用下面的命令读取开发环境的默认设置。

```sh
curl http://127.0.0.1:8080/api/v1/tenants/demo/users/alice/agents/assistant/settings
```

| 方法和相对路径 | 用途 |
| --- | --- |
| `GET /settings` | 读取记忆设置。 |
| `PUT /settings` | 更新 `enabled`、`provider`、`contextRounds` 和 `filterSensitive`。 |
| `POST /sessions/{session}/messages` | 追加消息，例如 `{"messages":[{"role":"user","content":"记住这个偏好"}]}`。 |
| `POST /sessions/{session}/context` | 用 `{"message":"当前问题"}` 查询短期上下文与长期记忆。 |
| `POST /sessions/{session}/turn` | 用相同的请求体生成一轮回复，需要配置模型。 |
| `POST /sessions/{session}/end` | 提交会话结束后的长期记忆处理，返回 `202 Accepted`。 |

## 源码与测试

- [src/memory/](src/memory/) 包含启动类和各业务包。
- [test/memory/](test/memory/) 按对应的业务包存放测试。
- [resources/](resources/) 存放 Spring Boot 配置和 Flyway 迁移。

Maven 在根目录的 `pom.xml` 中声明这三个目录。生产代码在 `memory` 包下按职责分组。

| `src/memory/` 下的目录 | 职责 |
| --- | --- |
| `api/` | HTTP 路由和请求鉴权 |
| `conversation/` | 会话读写、短期记忆、摘要、后台任务及其线程池 |
| `longterm/` | 长期记忆、记忆判断和供应商实现 |
| `llm/` | 模型调用和 token 计算 |
| `model/` | 会话标识和消息结构 |

现有行为测试位于对应的测试包，使用 `mvn clean verify` 运行。测试中的模型、存储和缓存依赖由测试替身提供。
