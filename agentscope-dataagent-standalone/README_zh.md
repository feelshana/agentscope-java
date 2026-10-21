# agentscope-dataagent

例行任务首发仅保留占位入口，旧读写API全部关闭，后续再完善调度与隔离。当前完成情况和未修P1统一见[上线总览](docs/go-live-gap-review-20261012.md)。


> 🇬🇧 English version: [README.md](README.md)

## 项目概览

外部表关联每表最多读取1000行，在内存提取列样例，不执行全表 COUNT 或逐列 DISTINCT。总行数显示“未统计”，上传文件仍显示准确导入行数；管理者可在数据集详情刷新样本。建模系统上下文包含限长摘要，问数读取已发布的官方工程规则。样本不代表完整枚举、唯一性或精确统计。详见 [ADR 0080](docs/adr/0080-bounded-sampling-and-wren-context.md) 与 [spec 068](docs/specs/068-bounded-sampling-and-wren-context.md)。

图表归属升级：已有平台表且使用 `ddl-auto=validate` 时，启动前执行 [0083迁移](docs/migrations/0083-chart-owner-mysql.sql)。新图仅提问用户本人可读；旧归属为空的图保留但不展示、不自动回填，需重新生成。

升级需要平台表新增可空 `sample_summary_json LONGTEXT` 列。默认 JPA `update` 自动补列；使用 `validate` 的部署须先执行 [MySQL 迁移脚本](docs/migrations/0080-dataset-sampling-mysql.sql)。历史数据不在启动时自动采样，外部表可由管理者手动刷新。

dataagent 让公司里**每位数据分析师都有一个属于自己的数据 agent** —— 它会摸熟你们组用的数据源、习惯的报表风格、踩过的坑，越用越顺手。

设计上有三件事最能说明它的不同：

- **多人并行进化、互不干扰。** 每个用户都有自己的私有 workspace。他教 agent 的技能、调出来的子智能体、积累下来的记忆，都是他自己的 —— 不会溢到别人那边。每个人拿到的初始 agent 是一样的，但它在不同人手里会长成不一样的模样。
- **能力市场，不是大杂烩。** 当某个人确实磨出了一份值得共享的东西 —— 一项 SQL 技能、一个子智能体、一段 memory 备忘 —— 可以把它提名出来；管理员审完通过后，这份内容会进到一个共享库里，下一次别人的 agent 启动就会自动看到。知识自下而上流动，但中间有道闸。
- **应用管理沙箱。** Python 脚本在每用户 Docker 沙箱内执行，UserSandboxRegistry 管理租约、懒启动、空闲回收和容量驱逐；部署配置镜像、资源规格与实例标签。建模 Agent 使用受工程权限约束的宿主文件系统。

Redis 可协调会话及共享状态；Docker 沙箱、MCP 池和容量预算仍在各进程内。多副本需粘性路由、持久目录共享和容量规划，见 [集群部署边界](docs/cluster-deploy.md)。

### 一览

| | dataagent |
|---|---|
| **适用场景** | 一组数据分析师，每人都需要一个会进化的 SQL / 图表 / 报表 agent，并希望把内部经验沉淀到共享库 |
| **用户数** | 多人 —— 每人一个私有 workspace，外加可选的侧边通道 |
| **隔离** | 按 `(用户, agent)` 划分的私有 workspace；脚本跑在隔离 sandbox |
| **自进化** | ✅ per-用户进化；**外加**经审批的贡献会进入共享库 |
| **Sandbox 生命周期** | **由应用方掌握** —— 在 agent 运行时之外完成创建、规格、回收 |
| **通道** | 内置 Web UI · 钉钉 · 通用 HTTP Webhook |
| **分布式** | 可选 Redis 协调；本地沙箱及预算仍需粘性路由和共享存储规划 |
| **文件系统** | 脚本执行用 `SandboxFilesystem`；私有 / 共享技能融合用 `OverlayFilesystem` |

### 架构

dataagent 跑在 **HarnessAgent + `SandboxFilesystem`** 之上，sandbox 生命周期由应用方持有（你决定 sandbox 何时、何处、以什么形态存在 —— 而不是运行时）。sandbox 之上挂着一个 `OverlayFilesystem`，把 per-用户的 `RemoteFilesystem`（上层、可写）和全局 `shared/` 目录（下层、只读，由 marketplace 审批流维护）融合起来。

```
┌───────────────────────────────────────────────────────────────────────┐
│  agentscope-dataagent（端口 8080，Spring Boot WebFlux）               │
│                                                                       │
│   React SPA ──▶ REST API (JWT)                                        │
│                  │                                                    │
│                  ▼                                                    │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  HarnessGateway                                                 │ │
│   │   ├ data-agent              （内置骨架，GLOBAL）                │ │
│   │   └ uda-{userId}-{agentId}  （每用户 fork、每租户独占）         │ │
│   └────────────────────────┬────────────────────────────────────────┘ │
│                            ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  per-(userId, agentId) 文件系统栈                               │ │
│   │   ┌──────────────────────────────────────────────────────────┐  │ │
│   │   │ OverlayFilesystem  （skills/、subagents/）               │  │ │
│   │   │   ┌── 上层：per-用户 RemoteFilesystem（可写） ──┐        │  │ │
│   │   │   └── 下层：shared/{skills,subagents}（只读）   │        │  │ │
│   │   └──────────────────────────────────────────────────────────┘  │ │
│   │   memory/、MEMORY.md、sessions/、tasks/   ← per-用户 RemoteFS  │ │
│   │   knowledge/、AGENTS.md                   ← 共享（只读）       │ │
│   │                                                                 │ │
│   │   SandboxFilesystem  （脚本执行）                               │ │
│   │     ▲ 生命周期由应用方管理（而不是运行时）                      │ │
│   └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│   能力市场:  用户贡献 ─▶ 管理员审批 ─▶ shared/ 持续生长               │
│   通道:      chatui · dingtalk · 通用 webhook                         │
│   存储:      默认嵌入式 H2（生产可切到 MySQL / PostgreSQL）           │
└───────────────────────────────────────────────────────────────────────┘
```

会话上下文默认使用平台MySQL中的`dataagent_agent_state`最新快照，H2开发使用持久JSON文件。框架按用户/会话加载，重启后可继续已保存状态；生产validate先执行[0084迁移](docs/migrations/0084-agent-state-mysql.sql)。这不代表沙箱、网关门锁及事件总线已支持任意多副本，见[ADR0084](docs/adr/0084-durable-mysql-agent-state.md)。

---

## 默认包含

- **内置 `data-agent` / `modeling-agent`** —— 分别负责已发布工程问数与官方 Wren 对话建模。子 Agent 定义保留，`dataagent.agent.subagents-enabled=false` 默认关闭编排。
- **per-用户数据 agent** —— 任一登录用户都可以 fork 内置或新建。每个用户 agent 拥有独立的 `CompositeFilesystem`，按 `(userId, agentId)` 切分；`skills/` 和 `subagents/` 是 `OverlayFilesystem`，下层是磁盘上共享的内容。
- **通道（v1）** —— `chatui`（默认开启、主用）、`dingtalk`（可选，原样从 agentscope-builder 移植）以及全新的**通用 Webhook** 通道（HMAC 签名入站，回调 / 长轮询出站）。
- **能力市场** —— 用户从自己 workspace 中提名 skill / 子智能体 / memory 片段作为 *contributions*。管理员在 Approvals 页审批，通过后内容落到运行态 `shared/` 目录（由 `src/main/resources/shared` 物化、已 gitignore），下次构建时所有租户都能看到。
- **当前 Wren 工具链** —— 问数使用 `wren_list_tools` / `wren_call_tool` 发现并透传官方 MCP，配合 `render_chart` 和沙箱 `run_python`；建模使用官方 skills、原生文件工具及 `wren_cli`。不注册旧 WrenToolkit、retrieve_evidence 或旧 ModelingToolkit。普通/ref_sql 模型、关系、视图、Cube 和 knowledge 遵循官方契约，SQL 支持 LIMIT/OFFSET，平台不改写 MDL。问题可选，建模完成后页面显式发布；上传或关联表可发布基础 MDL。
- **知识库共享与容量** —— QUERY 使用已发布模型，MANAGE 管理建模/发布/成员；运行用户、私有会话和附件不随库共享改变。上传采用大小/解析限制、准入和失败回滚；问数有全局/每用户执行与等待名额及 SSE 排队提示，预算均为单进程。配置与发布见 [平台并发说明](docs/platform-concurrency-and-release.md)。

---

## per-用户隔离

每个 `HarnessAgent` 都跑在 `WorkspaceManagerFactory.forAgent(ownerId, agentId)` 构建出来的 `CompositeFilesystem` 上：

| 路径 | 挂载方式 |
|---|---|
| `memory/`、`MEMORY.md`、`sessions/`、`tasks/` | `RemoteFilesystem`，按 `(ownerId, agentId)` 命名空间隔离 |
| `skills/`、`subagents/` | `OverlayFilesystem` —— 上层是 per-用户 `RemoteFilesystem`，下层是共享目录 `shared/{skills,subagents}/` |
| `knowledge/`、`AGENTS.md` | 磁盘上的共享根目录，对租户只读挂载（变更只能走能力市场贡献流程） |

Agent上下文通过框架状态存储持久化，沙箱文件仍按当前单机部署方式保存；开启旧Redis配置块不会自动切换这些存储。已有完整展示历史不等于遗失的Agent状态可自动重建。

---

## 能力市场流程

1. 用户（或 agent 自己通过 `contribute_to_workspace` 工具）从自己的 workspace 提交一个文件作为贡献：
   ```http
   POST /api/me/contributions
   { "targetType": "skill",
     "targetPath": "cohort-builder/SKILL.md",
     "rationale": "...",
     "payload": "<file contents>" }
   ```
2. 贡献以 `PENDING` 状态持久化，呈现在管理员的 `/admin/approvals` 页。
3. 管理员通过 → payload 落到 `~/.agentscope/dataagent-standalone/workspace/shared/skills/cohort-builder/SKILL.md`。
4. 每个 per-`(userId, agentId)` 的 overlay 在下次构建时就能看到，无需重启即对所有租户可见。

能力市场的颗粒度故意比 per-agent 的 ACL 共享更细 —— 单个贡献的单位是一项 skill / 一个子智能体 / 一段 memory，而不是整个 agent。

---

## 快速开始

### 1. 配置模型

dataagent 默认对接任意 OpenAI 兼容端点 —— 通过环境变量设置 key（以及非
OpenAI 官方时的 base URL）：

```bash
# OpenAI 官方
export OPENAI_API_KEY=sk-...

# 任意 OpenAI 兼容服务（DeepSeek、vLLM、one-api、DashScope compatible-mode 等）
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.deepseek.com/v1
export OPENAI_MODEL_NAME=deepseek-chat   # 可选，默认 gpt-4o
```

同时设置时，`DATAAGENT_OPENAI_*` 前缀的变量优先于裸的 `OPENAI_*`。
也可以退回 DashScope：设置 `DASHSCOPE_API_KEY=sk-...` 即可；或者提供你自己的
`Model` Spring Bean 切到其他 provider。

### 2.（可选）启用 Redis 做分布式部署

```yaml
dataagent:
  session:
    redis:
      enabled: true
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD:}
      database: 0
      key-prefix: "dataagent:session:"
```

启用后：

- `RemoteFilesystem` 写入走 Redis —— 被路由到副本 R2 的租户能看到 R1 写到 `memory/` / `sessions/` 等的内容。
- `ToolEventBus` 切成 Redis Pub/Sub —— R2 上的 SSE 消费方能收到 R1 在同一 session 触发的 tool-call 事件。
- 启动前置检查：如果 `dataagent.workspace` 指向临时路径（`/tmp/`、`/var/tmp/` 等），会拒绝启动。

3 副本部署详见 [`docs/cluster-deploy.md`](docs/cluster-deploy.md)。

### 3.（可选）启用 Webhook 侧通道

在 `~/.agentscope/dataagent/agentscope.json`：

```json
{
  "channels": {
    "ops-webhook": {
      "type": "webhook",
      "defaultAgentId": "data-agent",
      "dmScope": "MAIN",
      "properties": {
        "sharedSecret": "${WEBHOOK_SECRET}",
        "allowedIps": ["10.0.0.0/8"]
      }
    }
  }
}
```

外部系统这样调用：

```http
POST /api/webhook/ops-webhook/inbound
X-DataAgent-Sig: <body 的 HMAC-SHA256，hex>
{ "externalUserId": "alice@corp",
  "externalSessionId": "ticket-1234",
  "message": "how many users signed up yesterday?",
  "replyMode": "callback",
  "callbackUrl": "https://ops.internal/webhook/dataagent" }
```

回复要么 POST 回 `callbackUrl`（同样的 HMAC），要么在 `replyMode=poll` 时停在 `GET /api/webhook/ops-webhook/outbound/{inboundId}` 等长轮询取走。

### 4. 运行

```bash
java -jar target/agentscope-dataagent-*-exec.jar
```

打开 **http://localhost:8080** 登录。当前 application.yml 默认使用 MySQL 平台库，`--spring.profiles.active=h2` 切换文件 H2。SQL 初始化由 DATAAGENT_SQL_INIT_MODE/PLATFORM 单独控制，内置演示脚本创建 bob/alice；Java 本地管理员初始化需显式启用 `dataagent.seed-demo-users=true`（默认 false）。失效令牌由前端校验后返回登录页，提示重新登录。

---

## 配置参考（`application.yml`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `dataagent.jwt.secret` | 部署配置 | HS256 密钥至少 32 UTF-8 字节；所有 profile 都拒绝空值、开发占位值及短密钥。 |
| `dataagent.jwt.ttl-seconds` | `1800` | JWT固定有效期，刷新不续期。 |
| `dataagent.auth.password-login-enabled` | `true` | 本地密码登录；prod强制关闭接口及表单。 |
| `dataagent.auth.bi.enabled` / `bi.shared-key` | `false` / 空 | prod强制启用BI，需32字节Base64共享密钥，与JWT密钥分开。前端持钥可伪造用户名，包括管理员。 |
| `dataagent.workspace` | 为空时使用 JVM 工作目录 | 运行状态目录，与 `~/.agentscope/dataagent/agentscope.json` 分开；部署显式配置持久目录。 |
| `dataagent.workspace-store.local.max-file-size-mb` | `10` | `RemoteFilesystem` 本地后端的单文件上限。 |
| `dataagent.openai.api-key` | 空 | 启动兼容凭据，环境变量`DATAAGENT_OPENAI_API_KEY`或`OPENAI_API_KEY`；页面应用连接后由所选服务接管，无代码内Key默认值。 |
| `dataagent.model-services.encryption-key` | 空 | `DATAAGENT_MODEL_ENCRYPTION_KEY`：独立32字节Base64主密钥。空时首次保存生成`${user.home}/.agentscope-dataagent/model-services.key`；必须与平台库配套备份恢复。全局管理员通过“更多 > 大模型服务”配置/测试/应用，详见[单机手册](docs/single-host-release.md)。 |
| `dataagent.openai.base-url` | `https://api.deepseek.com` | 任意 OpenAI 兼容端点的 base URL（DeepSeek、vLLM、one-api、DashScope compatible-mode 等），尾部 `/v1` 可写可不写。环境变量：`DATAAGENT_OPENAI_BASE_URL` 或 `OPENAI_BASE_URL`。 |
| `dataagent.openai.model-name` | `deepseek-flash` | OpenAI 兼容模型 id。环境变量：`DATAAGENT_OPENAI_MODEL_NAME` 或 `OPENAI_MODEL_NAME`。 |
| `dataagent.openai.stream` | `true` | 是否以 SSE 流式输出。 |
| `dataagent.dashscope.api-key` | _空_ | DashScope API key（未配置 OpenAI key 时的回落）。环境变量：`DASHSCOPE_API_KEY`。 |
| `dataagent.dashscope.model-name` | `qwen3.8-max` | DashScope 模型 id。 |
| `dataagent.agent.name` | `data-agent` | 自动生成 `~/.agentscope/dataagent/agentscope.json` 时的 agent 名 |
| `dataagent.agent.sys-prompt` | _（内置提示词）_ | 覆盖首次启动时 scaffold 进 `workspace/AGENTS.md` 的内置系统提示词。推荐用环境变量 `DATAAGENT_AGENT_SYS_PROMPT`；**不要**把该键设为空值（属性存在但为空会 scaffold 出空提示词） |
| `dataagent.agent.subagents-enabled` | `false` | 子代理编排（harness `agent_spawn` / `agent_send` / `task_*`）。默认关闭：否则 harness 每轮会注入固定约 6.2k 字符的 `## Subagents` 提示词段与相应工具 schema。设为 `true` 即恢复该段、这些工具以及 `workspace/subagents/*.md` 的加载，无需改代码；**不影响**网关、会话路由与聊天。环境变量：`DATAAGENT_SUBAGENTS_ENABLED` |
| `dataagent.channels.chatui.enabled` | `true` | 主 Web 通道，默认开启 |
| Agent状态存储（自动选择） | 平台MySQL；H2开发JSON文件 | 共用spring.datasource；DDL跟随spring.jpa.hibernate.ddl-auto，见ADR0084；自定义AgentStateStore Bean优先 |
| `dataagent.session.redis.enabled` | `false` | 历史保留项，当前默认装配不读取；不自动启用分布式存储 |
| `dataagent.session.redis.host/port/password/database` | `localhost:6379/0` | Redis 连接 |
| `dataagent.session.redis.key-prefix` | `dataagent:session:` | Redis key 前缀 |
| `dataagent.marketplace.enabled` | `true` | 关掉则隐藏贡献 + 审批 API |
| `dataagent.marketplace.max-contribution-bytes` | `1048576` | `POST /api/me/contributions` 接受的最大 payload |
| `dataagent.wren.executable` | `wren` | WrenAI CLI 可执行文件（安装：`pip install -r requirements-wren.txt`，需 Python ≥ 3.11）。默认值自动探测 `~/.local/bin`、`/usr/local/bin`、`/usr/bin` 与 PATH——Linux 部署装好后零配置；Windows 开发机指向 venv 的 `wren.exe` 绝对路径。spawn 失败会回显安装引导。环境变量：`DATAAGENT_WREN_EXECUTABLE` |
| `dataagent.wren.mdl-home` | _空_ → `~/.agentscope/dataagent/mdl` | MDL 产物根目录：生成工程 `project/`、已发布快照 `published/`（含 M4 连接钉入标记 `wren-source.properties`）、平台清单 `mdl.json`（语义建模发布流，specs/010 M2/M3/M4）；同目录下 `.wren/profiles.yml` 为平台托管的运行期连接档案——除默认条目外每个已配置的 MySQL 外部数据源各一条（M4）。环境变量：`DATAAGENT_WREN_MDL_HOME` |
| `dataagent.wren.profile` / `data-source` / `timeout-seconds` / `instance-idle-seconds` | `dataagent` / `mysql` / `180` / `1800` | 默认 wren 连接档案名（M4 起为回落值：全部数据集来自同一 MySQL 外部源的组在发布期解析专属 `ext-<datasourceId>` 档案并钉入快照；本配置仅作用于纯上传组与 M4 之前存量快照）、工程方言（写进 `wren_project.yml` 并传给 parse-types）、子进程超时（秒）、实例池空闲回收阈值（秒，M3：超时未用的 per-知识库 wren 实例被关闭，下次查询按需重建）。环境变量：`DATAAGENT_WREN_PROFILE` / `DATAAGENT_WREN_DATA_SOURCE` / `DATAAGENT_WREN_TIMEOUT_SECONDS` / `DATAAGENT_WREN_INSTANCE_IDLE_SECONDS` |
| `server.port` | `8080` | HTTP 端口 |
| `dataagent.seed-demo-users` / `cors.allowed-origins` | `false` / `*` | Java 管理员初始化需启用；CORS 按部署配置允许来源。SQL 初始化是独立开关。 |
| `dataagent.upload.max-file-size` / `max-selection-size` / `max-files` | `50MB` / `100MB` / `10` | 单文件、整批选择和文件数限制，前端读取服务端策略。 |
| `dataagent.upload.max-rows` / `max-columns` / `max-cells` | `500000` / `100` / `10000000` | application.yml 解析上限，可由外置配置调整；不代表已完成 50 万行容量验收。 |
| `dataagent.upload.concurrent-files` | `8` | 单进程在途文件预算，允许 1—32，满额 429。 |
| `dataagent.chat.max-active` / `max-active-per-user` | `16` / `2` | ChatController 问数执行名额，建模豁免。 |
| `dataagent.chat.max-waiting` / `max-waiting-per-user` | `16` / `1` | 全局/每用户等待名额。 |
| `dataagent.chat.wait-seconds` / `execution-seconds` | `30` / `600` | 等待/执行超时。 |
| `dataagent.wren.max-instances` / `call-wait-seconds` | `16` / `30` | MCP 实例预算/每库门锁等待秒数，不含普通 CLI 进程。 |
| `dataagent.sandbox.max-containers` | `16` | 沙箱上限，满额只驱逐无活动租约的容器。 |
| `dataagent.openai.http-version` | `HTTP_1_1` | 默认模型 HTTP 协议。 |

用户、agent、授权、历史等元数据当前默认使用 MySQL，h2 profile 使用文件 H2。切换后端需明确配置 DATAAGENT_DB_URL/DRIVER/USER/PASSWORD；PostgreSQL 还需匹配的初始化 SQL 和方言。上传数据集物理表使用另一组 `dataagent.dataset.datasource` MySQL 配置。

---

## API 端点

### 公开

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/login` | 登录，返回 JWT |
| `GET` | `/api/auth/options` | 非秘密入口配置，控制本地/生产登录展示 |
| `POST` | `/api/auth/bi-login` | 加密ticket换取JWT，未知用户名自动创建普通用户 |
| `POST` | `/api/webhook/{channelId}/inbound` | 通用 webhook 入站（必须带 HMAC） |
| `GET` | `/api/webhook/{channelId}/outbound/{inboundId}` | `poll` 模式下长轮询取回复 |

### 用户级（任何登录用户）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/auth/me` | 当前用户信息 |
| `GET` | `/api/agents/{id}` | 内置 `data-agent` 元数据 |
| `POST` | `/api/agents/{agentId}/chat/stream` | SSE 流式对话（请求体 `{ message }`） |
| `POST` | `/api/agents/{agentId}/chat/send` | 同步对话（请求体 `{ message }`） |
| `GET` | `/api/agents/{agentId}/sessions` | 列出自己的 session |
| `GET` | `/api/agents/{agentId}/sessions/{key}/history` | session 消息历史 |
| `GET` | `/api/agents/{agentId}/sessions/{key}/turns` | 按轮次的 transcript |
| `GET` | `/api/agents/{agentId}/sessions/{key}/tree` | 子 agent fan-out 树 |
| `POST` | `/api/agents/{agentId}/sessions/{key}/reset` | 重置 session |
| `GET` `POST` | `/api/agents` | 列出 / 新建 per-用户数据 agent |
| `GET` `POST` | `/api/agents/{id}/skills` | 列出 / 写入 workspace 中的 skill |
| `GET` `POST` | `/api/agents/{id}/tools` | 列出 / 注册自定义工具 |
| `POST` | `/api/agents/{sourceId}/clone` | fork 一个内置或自己的 agent |
| `GET` `POST` | `/api/user/bindings` | 列出 / 新增通道偏好 |
| `PUT` `DELETE` | `/api/user/bindings/{index}` | 更新 / 删除偏好 |
| `GET` `POST` | `/api/me/contributions` | 列出自己的贡献 / 提交新贡献 |

### 管理员级（`ROLE_ADMIN`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` `POST` | `/api/admin/users` | 列出 / 新建用户 |
| `GET` | `/api/admin/runtime/overview` | 平台概览 |
| `GET` | `/api/admin/runtime/instances` | 已注册的 agent 实例 |
| `GET` | `/api/admin/runtime/sessions` | 全部 session |
| `GET` | `/api/admin/runtime/channels` | 通道状态 |
| `GET` | `/api/admin/contributions?status=PENDING\|APPROVED\|REJECTED` | 列出贡献 |
| `POST` | `/api/admin/contributions/{id}/approve` | 通过 → 写入 `shared/` |
| `POST` | `/api/admin/contributions/{id}/reject` | 驳回，附评审备注 |
| `GET` | `/api/admin/config/agentscope` | 原始 `agentscope.json` |
| `GET` | `/api/admin/channels/{channelId}/bindings` | 某通道的路由绑定 |
| `GET` | `/api/admin/usage/...` | 用量汇总（per-用户 / per-agent / 按小时 / 按天） |

---

## 构建

本项目是 standalone，根 pom.xml 的 Java 编译基线为 17。执行根目录 `mvn test`、`mvn package -DskipTests`，前端在 frontend 下 `npm run build`。框架代码变更须先安装同版本 harness/OpenAI 扩展，沙箱取消机制须配套新镜像，详见 [发布说明](docs/platform-concurrency-and-release.md)。

```bash
mvn package -DskipTests
```

输出：

- `target/agentscope-dataagent-<version>-exec.jar` —— 可执行 fat JAR
- `target/agentscope-dataagent-<version>.jar` —— thin 库 JAR
- `target/dataagent-<version>.tar.gz` —— Linux 单机发布包（bin/conf/lib）

默认构建当前前端。公共默认配置与 h2 profile 保留在 JAR；本地 application-dev/prod 的 yml/yaml/properties 不进入 JAR 或发布包。分发仅提供无凭据配置样例；部署时创建外置配置。Linux 脚本检查 Wren 依赖并等待应用健康，持久状态位于版本目录外。操作步骤见[单机发布](docs/single-host-release.md)。

格式检查（CI 会卡）：

```bash
mvn spotless:check
```
