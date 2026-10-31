# agentscope-dataagent

> 🇨🇳 中文版：[README_zh.md](README_zh.md)

## Overview

Current behavior ([ADR 0089](docs/adr/0089-lightweight-date-gate-and-native-history.md)): modeling creates views only after an explicit user request and confirmation of the structure. Historical views are left untouched. Before Wren tool discovery, a lightweight question gate asks for missing or ambiguous date ranges without rewriting the question or inventing dates. Clarifications use native conversation history; there is no separate persistent date state. This is a question-level policy, not SQL-plan enforcement. Per-turn model, tool and gate durations are written to `logs/archive-20261020/execution-timing.log`.

External table association samples at most 1,000 rows per table, without full-table COUNT or per-column DISTINCT. Total row count is unknown (`rowCount: null`); file uploads retain their exact imported count. Managers can refresh samples from the dataset page. Samples are observations, not complete enums or exact statistics. Modeling receives a bounded summary; querying reads published Wren rules. See [ADR 0080](docs/adr/0080-bounded-sampling-and-wren-context.md) and [spec 068](docs/specs/done/068-bounded-sampling-and-wren-context.md).

Schema upgrade: the platform database needs nullable `dataagent_dataset.sample_summary_json LONGTEXT`. The default JPA `update` mode adds it; deployments using `validate` must apply the [MySQL migration](docs/migrations/0080-dataset-sampling-mysql.sql) before starting the new JAR. Existing records remain unsampled until explicitly refreshed; no startup scan is performed. Later schema changes have their own scripts, also needed only under `validate`: [modeling revision columns](docs/migrations/0093-modeling-revision-mysql.sql) and [fact-table query windows](docs/migrations/0094-fact-query-window-mysql.sql) (`dataagent_fact_query_window` table and `dataagent_dataset_group.fact_query_window_days`) and [field screening](docs/migrations/0095-field-screening-mysql.sql) (`dataagent_dataset.field_screening_confirmed`).

Modeling uses a generic AgentScope agent with the installed official Wren skills, native
file operations, and a project-scoped CLI adapter (ADR 0068 / spec 057). The adapter binds
the authenticated tenant's draft project and connection; it does not define a second
modeling workflow. File changes and official memory store requests use native HITL.
Official validate/build/query results are returned with their exit codes. Building the
draft does not publish it: the modeling page explicitly publishes the snapshot used for
SQL-only asking. See [the architecture](ARCHITECTURE_zh.md) for the current execution boundary.

dataagent is a place where every analyst in your company gets their own data
agent — one that learns the team's data sources, picks up the team's
reporting conventions, remembers the team's gotchas, and gets sharper the
more they use it.

Three things shape the design:

- **Side-by-side evolution.** Each user has their own private workspace.
  Whatever skills they teach, whatever sub-agents they shape, whatever
  memory their agent builds up — it stays theirs, and doesn't bleed into
  anyone else's. Everyone starts from the same agent; how it grows up is
  personal.
- **A capability marketplace, not a free-for-all.** When someone grows
  something genuinely useful — a SQL skill, a sub-agent, a note in memory —
  they can nominate it for sharing. An admin reviews it, and if approved,
  it lands in a shared library that every other user's agent picks up
  automatically. Knowledge moves bottom-up, but with a gate.
- **Application-managed sandboxes.** Python scripts run in per-user Docker
  sandboxes. UserSandboxRegistry manages leases, lazy startup, idle collection
  and capacity eviction; deployments configure the image, resource limits and
  instance labels. The modeling agent uses a scoped host filesystem.

Redis can coordinate sessions and shared state. Docker sandboxes, MCP pools and
capacity budgets remain process-local: production replicas need sticky routing,
shared durable storage and explicit resource budgets. See [deployment constraints](docs/guides/cluster-deploy.md).

### At a glance

| | dataagent |
|---|---|
| **Use it when** | A team of analysts each needs their own evolving SQL / chart / report agent, with a shared library of in-house know-how |
| **Users** | Many — each gets a private workspace, plus optional side channels |
| **Isolation** | Private workspace per `(user, agent)`; scripts run in an isolated sandbox |
| **Self-evolution** | ✅ Per user — **plus** approved contributions are shared with everyone |
| **Sandbox lifecycle** | **Owned by the application** — provisioned, sized and recycled outside the agent runtime |
| **Channels** | Built-in web UI · DingTalk · generic HTTP webhook |
| **Distribution** | Optional Redis coordination; local sandboxes and budgets require routing and shared-storage planning |
| **Filesystem** | `SandboxFilesystem` for script execution + `OverlayFilesystem` for blending shared / private skills |

### Architecture

dataagent is built on **HarnessAgent + `SandboxFilesystem`** with sandbox
lifecycle owned by the application (you decide when, where, and how the
sandbox lives — not the runtime). Above the sandbox sits an `OverlayFilesystem`
that blends a per-user `RemoteFilesystem` (top, writable) with the global
`shared/` directory (bottom, read-only, populated by the marketplace approval
flow).

```
┌───────────────────────────────────────────────────────────────────────┐
│  agentscope-dataagent (port 8080, Spring Boot WebFlux)                │
│                                                                       │
│   React SPA ──▶ REST API (JWT)                                        │
│                  │                                                    │
│                  ▼                                                    │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  HarnessGateway                                                 │ │
│   │   ├ data-agent              (built-in skeleton, GLOBAL)         │ │
│   │   └ uda-{userId}-{agentId}  (per-user fork, per-tenant)         │ │
│   └────────────────────────┬────────────────────────────────────────┘ │
│                            ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │  Per-(userId, agentId) Filesystem stack                         │ │
│   │   ┌──────────────────────────────────────────────────────────┐  │ │
│   │   │ OverlayFilesystem (skills/, subagents/)                  │  │ │
│   │   │   ┌── upper: per-user RemoteFilesystem  (writable) ──┐   │  │ │
│   │   │   └── lower: shared/{skills,subagents}    (RO)        │   │  │ │
│   │   └──────────────────────────────────────────────────────────┘  │ │
│   │   memory/, MEMORY.md, sessions/, tasks/  ← per-user RemoteFS    │ │
│   │   knowledge/, AGENTS.md                  ← shared (RO)          │ │
│   │                                                                 │ │
│   │   SandboxFilesystem (script execution)                          │ │
│   │     ▲ lifecycle managed by the application (not the runtime)    │ │
│   └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│   Marketplace:  user contributes ─▶ admin approves ─▶ shared/ grows   │
│   Channels:     chatui · dingtalk · generic webhook                   │
│   Storage:      embedded H2 by default (swap for MySQL/PG in prod)    │
└───────────────────────────────────────────────────────────────────────┘
```

With shared state enabled, the per-user filesystem, sessions and tool-event
bus are all backed by Redis, so any replica can serve any user. See
[`docs/guides/cluster-deploy.md`](docs/guides/cluster-deploy.md).

---

## What is included

- **Built-in `data-agent` and `modeling-agent`** — generic AgentScope execution
  with the installed official Wren skills. Sub-agent definitions are retained;
  orchestration is disabled by default (`dataagent.agent.subagents-enabled=false`).
- **Per-user data agents** — every authenticated user can fork the built-in or
  draft a new one. Each user-agent gets its own `CompositeFilesystem` keyed by
  `(userId, agentId)`; `skills/` and `subagents/` are an `OverlayFilesystem`
  with the shared on-disk content as the lower layer.
- **Channels (v1)**: `chatui` (always-on, primary), `dingtalk` (opt-in,
  ported intact from agentscope-builder), and a new **generic webhook** channel
  (HMAC-signed HTTP-in, callback or long-poll HTTP-out).
- **Capability marketplace** — users nominate skills, sub-agents, or memory
  snippets from their own workspace as *contributions*. Admins approve via the
  Approvals page; approved files land under the runtime `shared/` directory
  (materialised from `src/main/resources/shared`, git-ignored) and become
  visible to every tenant on the next agent build (via the overlay's lower
  layer).
- **Official Wren tools** — modeling uses native scoped file tools and `wren_cli`;
  querying uses `wren_list_tools` and `wren_call_tool` to expose the current
  published project's official MCP schema and arguments unchanged. The platform
  adds tenant authorization, publication, result artifacts, `run_python` and
  `render_chart`; it does not add SQL, metric routing, custom retrieval, or rewrite MDL.
  Ordinary models, SQL-defined models, relationships, views and Cubes are supported.
  Uploads and external-table associations build and publish a baseline MDL.

---

## Per-user isolation

Every `HarnessAgent` runs on a `CompositeFilesystem` built by
`WorkspaceManagerFactory.forAgent(ownerId, agentId)`:

| Path | Mount |
|---|---|
| `memory/`, `MEMORY.md`, `sessions/`, `tasks/` | `RemoteFilesystem` namespaced by `(ownerId, agentId)` |
| `skills/`, `subagents/` | `OverlayFilesystem` — per-user `RemoteFilesystem` on top of the shared `shared/{skills,subagents}/` directory |
| `knowledge/`, `AGENTS.md` | Shared on-disk root, mounted read-only for tenants (mutations only go through the marketplace contribution flow) |

The same `RemoteFilesystem` is backed by Redis (and survives replica
restarts) when `dataagent.session.redis.enabled=true`; otherwise it is a local
file store. See [`docs/guides/cluster-deploy.md`](docs/guides/cluster-deploy.md).

---

## Marketplace flow

1. A user (or the agent itself, via the `contribute_to_workspace` tool) submits
   a file from their workspace as a contribution:
   ```http
   POST /api/me/contributions
   { "targetType": "skill",
     "targetPath": "cohort-builder/SKILL.md",
     "rationale": "...",
     "payload": "<file contents>" }
   ```
2. The contribution is persisted with status `PENDING` and shown to admins on
   `/admin/approvals`.
3. Admin approves → the payload is materialised under
   `~/.agentscope/dataagent-standalone/workspace/shared/skills/cohort-builder/SKILL.md`.
4. Every per-`(userId, agentId)` overlay picks it up on the next agent build,
   immediately visible to every tenant without restart.

The marketplace is intentionally finer-grained than per-agent ACL sharing — the
unit of contribution is one skill / sub-agent / memory snippet, not an entire
agent.

---

## Getting started

### 1. Configure a model

dataagent talks to any OpenAI-compatible endpoint by default — set the key
(and, for non-OpenAI providers, the base URL) via environment variables:

```bash
# Official OpenAI
export OPENAI_API_KEY=sk-...

# Any OpenAI-compatible provider (DeepSeek, vLLM, one-api, DashScope compatible-mode, ...)
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.deepseek.com/v1
export OPENAI_MODEL_NAME=deepseek-chat   # optional, default gpt-4o
```

A `DATAAGENT_OPENAI_*` prefix takes precedence over the plain `OPENAI_*`
vars when both are set. Alternatively, fall back to DashScope by setting
`DASHSCOPE_API_KEY=sk-...`, or provide your own `Model` Spring bean to use a
different provider.

### 2. (Optional) Enable Redis for distributed deployment

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

When enabled:

- `RemoteFilesystem` writes route through Redis, so a tenant routed to replica
  R2 sees what R1 wrote to `memory/`, `sessions/`, etc.
- `ToolEventBus` becomes Redis Pub/Sub, so an SSE consumer on R2 sees tool-call
  events fired by R1 in the same session.
- Pre-flight refuses to start if `dataagent.workspace` points at an ephemeral
  path (`/tmp/`, `/var/tmp/`, …).

See [`docs/guides/cluster-deploy.md`](docs/guides/cluster-deploy.md) for a 3-replica
walkthrough.

### 3. (Optional) Enable the webhook side-channel

In `~/.agentscope/dataagent/agentscope.json`:

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

Then external systems call:

```http
POST /api/webhook/ops-webhook/inbound
X-DataAgent-Sig: <HMAC-SHA256 of body, hex>
{ "externalUserId": "alice@corp",
  "externalSessionId": "ticket-1234",
  "message": "how many users signed up yesterday?",
  "replyMode": "callback",
  "callbackUrl": "https://ops.internal/webhook/dataagent" }
```

Replies are POSTed to `callbackUrl` (same HMAC), or parked for long-poll at
`GET /api/webhook/ops-webhook/outbound/{inboundId}` when `replyMode=poll`.

### Build and distribute

`mvn package` rebuilds the current frontend and produces the existing Boot exec JAR
plus `target/dataagent-<version>.tar.gz` with `bin/conf/lib`. Public defaults and the
h2 profile stay in the JAR; private application-dev/prod YAML/properties are excluded
from resources, JARs and the archive. The archive ships only a credential-free
configuration example. The Linux launcher checks Wren dependencies, waits for HTTP
health and keeps durable state outside the version directory. See the
[single-host release guide](docs/guides/single-host-release.md).

### 4. Run

```bash
java -jar target/agentscope-dataagent-*-exec.jar
```

Open **http://localhost:8080** and log in. The checked-in configuration uses MySQL
for platform metadata; `--spring.profiles.active=h2` selects file-based H2.
SQL initialization is separately controlled by `DATAAGENT_SQL_INIT_MODE` and
`DATAAGENT_SQL_INIT_PLATFORM`; bundled demo SQL seeds `bob` and `alice`.
For isolated local development,
set `dataagent.seed-demo-users=true` to seed the initial administrator. It defaults
to false; existing users and grants are retained.

---

## Configuration reference (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `dataagent.jwt.secret` | deployment configuration | HS256 signing key: at least 32 UTF-8 bytes. Empty, development-placeholder and short keys are rejected in every profile. |
| `dataagent.seed-demo-users` | `false` | Opt in to Java administrator seeding for local development; does not disable separately configured SQL initialization. |
| `dataagent.cors.allowed-origins` | `*` | Allowed browser origin patterns; configure explicit origins for deployment. |
| `dataagent.workspace` | JVM working directory when blank | Runtime workspace, separate from `~/.agentscope/dataagent/agentscope.json`. Set an explicit durable directory in deployments. |
| `dataagent.workspace-store.local.max-file-size-mb` | `10` | Per-file cap for the `RemoteFilesystem` local store. |
| `dataagent.openai.api-key` | deployment configuration | OpenAI-compatible credentials; DATAAGENT_OPENAI_API_KEY or OPENAI_API_KEY. Configured OpenAI takes precedence over DashScope. |
| `dataagent.openai.base-url` | `https://api.deepseek.com` | Current application.yml fallback; override with DATAAGENT_OPENAI_BASE_URL or OPENAI_BASE_URL for the deployed provider. |
| `dataagent.openai.model-name` | `deepseek-flash` | Current application.yml fallback; override with DATAAGENT_OPENAI_MODEL_NAME or OPENAI_MODEL_NAME. |
| `dataagent.openai.stream` | `true` | Stream model completions over SSE. Env: `DATAAGENT_OPENAI_STREAM`. Set `false` to compare non-streaming model requests when diagnosing upstream EOF; browser chat still uses SSE. |
| `dataagent.web-search.api-key` | _(empty)_ | DeepSeek Search API key for the query agent's `web_search` (spec094). Env only: `DEEPSEEK_API_KEY`; empty disables the tool. Related: `dataagent.web-search.enabled` (`true`), `base-url` (`https://api.deepseek.com`), `timeout-seconds` (`20`), `max-results` (`5`). |
| `dataagent.dashscope.api-key` | _(empty)_ | DashScope API key (fallback when no OpenAI key is set). Env: `DASHSCOPE_API_KEY`. |
| `dataagent.dashscope.model-name` | `qwen3.8-max` | Current application.yml fallback; DATAAGENT_MODEL_NAME. |
| `dataagent.agent.name` | `data-agent` | Used when auto-generating `~/.agentscope/dataagent/agentscope.json`. |
| `dataagent.agent.sys-prompt` | _(built-in prompt)_ | Overrides the built-in system prompt scaffolded into `workspace/AGENTS.md` on first start. Prefer the `DATAAGENT_AGENT_SYS_PROMPT` env var, and never set this key to an empty value — an empty property would scaffold a blank prompt. |
| `dataagent.agent.subagents-enabled` | `false` | Harness subagent orchestration (`agent_spawn` / `agent_send` / `task_*`). Off by default: the harness otherwise injects a fixed ~6.2k-char `## Subagents` system-prompt section plus those tool schemas on every turn. Setting it to `true` restores the section, the tools and the loading of `workspace/subagents/*.md` — no code change. Does **not** affect the gateway, session routing or chat. Env: `DATAAGENT_SUBAGENTS_ENABLED`. |
| `dataagent.channels.chatui.enabled` | `true` | Primary web channel. Always-on by default. |
| `dataagent.session.redis.enabled` | `false` | Use Redis for distributed agent state. |
| `dataagent.session.redis.host/port/password/database` | `localhost:6379/0` | Redis connection. |
| `dataagent.session.redis.key-prefix` | `dataagent:session:` | Redis key namespace. |
| `dataagent.marketplace.enabled` | `true` | Disable to hide the contribution + approval API. |
| `dataagent.marketplace.max-contribution-bytes` | `1048576` | Max payload accepted by `POST /api/me/contributions`. |
| `dataagent.wren.executable` | `wren` | WrenAI CLI (`pip install -r requirements-wren.txt`, Python >= 3.11). The default is auto-probed at `~/.local/bin` / `/usr/local/bin` / `/usr/bin` and PATH — Linux deployments need no config; on Windows point it at the venv `wren.exe`. Spawn failures surface an installation hint. Env: `DATAAGENT_WREN_EXECUTABLE`. |
| `dataagent.wren.mdl-home` | _(empty)_ → `~/.agentscope/dataagent/mdl` | Root of the MDL artifacts: generated `project/`, published snapshot `published/` (including the M4 `wren-source.properties` connection marker) and the platform-side `mdl.json` manifest (semantic-modeling publish flow, specs/010 M2/M3/M4); `.wren/profiles.yml` under it is the platform-managed runtime connection profile — one entry per configured MySQL external datasource besides the default (M4). Env: `DATAAGENT_WREN_MDL_HOME`. |
| `dataagent.wren.skills-dir` | _(empty)_ → auto-inferred | Official wren skills root (`skills_content` inside the wrenai pip package, specs/022 / ADR 0035): mounted natively onto `modeling-agent` via the harness skill repository so `load_skill_through_path` serves the same playbook files the CLI does. Blank = inferred from the resolved executable location (pip `site-packages` layouts on Windows/Unix). Point it explicitly only at non-standard layouts; a missing/invalid directory logs a warning and the modeling agent falls back to the `wren_skills_get` CLI channel. Env: `DATAAGENT_WREN_SKILLS_DIR`. |
| `dataagent.wren.profile` / `data-source` / `timeout-seconds` / `instance-idle-seconds` | `dataagent` / `mysql` / `180` / `1800` | Default wren connection profile (M4: a group whose datasets all come from one MySQL external datasource binds its own `ext-<datasourceId>` profile, resolved at publish time and pinned into the snapshot; this setting is the fallback for uploaded-only groups and pre-M4 snapshots), project dialect (written into `wren_project.yml`, passed to parse-types), per-command subprocess timeout (seconds) and the per-group instance-pool idle TTL in seconds (M3: an unused wren instance is closed and respawned on demand). Env: `DATAAGENT_WREN_PROFILE` / `DATAAGENT_WREN_DATA_SOURCE` / `DATAAGENT_WREN_TIMEOUT_SECONDS` / `DATAAGENT_WREN_INSTANCE_IDLE_SECONDS`. |
| `server.port` | `8080` | HTTP port. |
| `dataagent.upload.max-file-size` / `max-selection-size` / `max-files` | `50MB` / `100MB` / `10` | 单文件、整批选择大小及文件数量；前端从上传策略 API 读取。 |
| `dataagent.upload.max-rows` / `max-columns` / `max-cells` | `500000` / `100` / `10000000` | 每文件解析规模限制。 |
| `dataagent.demo.enabled` / `samples-dir` / `jdbc-url` / `username` / `password` | `false` / `data/demo-samples` / `jdbc:mysql://localhost:3306/demo_data` / `demo_reader` / empty | Built-in demo files and read-only `demo_data` source (spec105). The two approved files ship in `deploy/demo-samples/` and are copied to `samples-dir`; the source needs a reader password. |
| `dataagent.upload.concurrent-files` | `8` | 单进程在途上传预算（1—32）；超额 429，前端有限调度。 |
| `dataagent.chat.max-active` / `max-active-per-user` | `16` / `2` | ChatController 问数执行预算，建模豁免。 |
| `dataagent.chat.max-waiting` / `max-waiting-per-user` | `16` / `1` | 问数等待名额；排队通过 SSE capacity 提示。 |
| `dataagent.chat.wait-seconds` / `execution-seconds` | `30` / `600` | 问数等待和执行超时。 |
| `dataagent.wren.max-instances` / `call-wait-seconds` | `16` / `30` | MCP 实例预算及每库门锁等待；不覆盖普通 CLI 子进程。 |
| `dataagent.sandbox.max-containers` | `16` | 每进程沙箱上限，满额只驱逐空闲容器。环境变量 DATAAGENT_SANDBOX_MAX_CONTAINERS。 |
| `dataagent.openai.http-version` | `HTTP_1_1` | 默认模型 HTTP 协议；环境变量 DATAAGENT_OPENAI_HTTP_VERSION。 |

The current default platform database is MySQL. The `h2` profile selects embedded
H2. Configure `DATAAGENT_DB_URL`, `DATAAGENT_DB_DRIVER`, `DATAAGENT_DB_USER` and
`DATAAGENT_DB_PASSWORD` explicitly when changing databases; PostgreSQL also needs
compatible initialization SQL and dialect settings. Uploaded physical tables
continue to use the separately configured dataset MySQL database.

共享权限、上传失败处理、问答排队及本次框架依赖/镜像发布步骤见 [平台并发与发布说明](docs/guides/platform-concurrency-and-release.md)。来源 ADR/spec 原文及编号映射见 [导入索引](docs/archive/imported/multiAgent-Mdl-lyf/README.md)。容量是单 JVM 约束，默认值不代表已完成真实负载压测。

---

## API endpoints

### Public

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Login, returns JWT |
| `POST` | `/api/webhook/{channelId}/inbound` | Generic webhook ingress (HMAC-required) |
| `GET` | `/api/webhook/{channelId}/outbound/{inboundId}` | Long-poll for the reply in `poll` mode |

### User-scoped (any authenticated user)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/auth/me` | Current user info |
| `GET` | `/api/agents/{id}` | Metadata for the built-in `data-agent` |
| `POST` | `/api/agents/{agentId}/chat/stream` | SSE streaming chat (request body: `{ message }`) |
| `POST` | `/api/agents/{agentId}/chat/send` | Synchronous chat (request body: `{ message }`) |
| `GET` | `/api/agents/{agentId}/sessions` | List own sessions |
| `GET` | `/api/agents/{agentId}/sessions/{key}/history` | Session message history |
| `GET` | `/api/agents/{agentId}/sessions/{key}/turns` | Per-turn transcript |
| `GET` | `/api/agents/{agentId}/sessions/{key}/tree` | Sub-agent fan-out tree |
| `POST` | `/api/agents/{agentId}/sessions/{key}/reset` | Reset a session |
| `GET` `POST` | `/api/agents` | List / create per-user data agents |
| `GET` `POST` | `/api/agents/{id}/skills` | List / write a skill in your workspace |
| `GET` `POST` | `/api/agents/{id}/tools` | List / register custom tools |
| `POST` | `/api/agents/{sourceId}/clone` | Fork a built-in or your own agent |
| `GET` `POST` | `/api/user/bindings` | List / add channel preferences |
| `PUT` `DELETE` | `/api/user/bindings/{index}` | Update / remove a preference |
| `GET` `POST` | `/api/me/contributions` | List own contributions / submit a new one |

### Admin-scoped (`ROLE_ADMIN`)

| Method | Path | Description |
|---|---|---|
| `GET` `POST` | `/api/admin/users` | List / create users |
| `GET` | `/api/admin/runtime/overview` | Platform overview |
| `GET` | `/api/admin/runtime/instances` | Registered agent instances |
| `GET` | `/api/admin/runtime/sessions` | All sessions |
| `GET` | `/api/admin/runtime/channels` | Channel state |
| `GET` | `/api/admin/contributions?status=PENDING\|APPROVED\|REJECTED` | List contributions |
| `POST` | `/api/admin/contributions/{id}/approve` | Approve → materialise under `shared/` |
| `POST` | `/api/admin/contributions/{id}/reject` | Reject with reviewer note |
| `GET` | `/api/admin/config/agentscope` | Raw `agentscope.json` |
| `GET` | `/api/admin/channels/{channelId}/bindings` | Routing bindings for a channel |
| `GET` | `/api/admin/usage/...` | Usage rollups (per-user / per-agent / hourly / daily) |

---

## Conversational modeling and querying

上传 / 关联数据 → 基本 Wren 模型 → 可选业务文档与批量问题 → 官方对话建模 → 助手检查 → 显式发布 → 问数。
官方同版本 generate-mdl/enrich-context/usage/onboarding skills 决定业务流程，项目只作通用 AgentScope 执行器。
业务文档保存后显示成功，可在对话中让助手读取；问题表格支持增加行数，预设问题和逐题验证均不阻碍发布。
草稿写权限可一次授权同一批待执行操作，也可逐项审阅；技术检查失败由助手修正。官方 build 不等于发布。

“查看语义模型”提供模型、关系、SQL 定义模型、视图、Cube、业务知识、MDL 与发布快照。
问数使用已发布工程与其 knowledge 原文，不读取未发布草稿；官方编译 `target/mdl.json` 保持原样。
查询结果仍可交给 Python/ECharts。最终答案点赞后，由官方 memory store 保存成功且标注用于答案的业务 SQL，
诊断查询不标注，点踩仅记录反馈。SQL 原样保存，不添加 LIMIT 或修改语义资产。

运行工程 `runtime/query-project` 合并已发布的 knowledge/sql 与 `runtime/liked-examples` 中的点赞样例。
索引只是官方衍生物，模型或样例源码变化时由官方 memory index 重建。调用 get_context/recall_queries 前准备索引；
其他 SQL 查询不因缺记忆依赖被阻断。使用官方检索，不提供平台自研评分或中文词项降级。

设计与验收：[ADR 0069](docs/adr/0069-wren-native-modeling-and-query-agents.md)、[spec 058](docs/specs/done/058-wren-native-modeling-and-query-agents.md)、[完整计划](docs/archive/plans/058-wren-native-modeling-and-query-agents.md)。

## Building

This is a standalone Maven project with Java 17 as its compilation baseline.
When harness or OpenAI-extension sources change, first install matching dependencies
from the parent repository as described in [the release guide](docs/guides/platform-concurrency-and-release.md).

```bash
mvn package -DskipTests
```

Outputs:

- `target/agentscope-dataagent-<version>-exec.jar` — executable fat JAR
- `target/agentscope-dataagent-<version>.jar` — thin library JAR

Format check (CI gates on this):

```bash
mvn spotless:check
```

### Wren dependencies and example retrieval

固定官方 `wrenai==0.15.0` / `wren-core-py==0.8.0` / `mcp==1.30.0`，使用 `memory-onnx` 依赖支持中文语义召回。Wren 0.15.0 使用的 FastMCP 导入与 MCP 2.x 不兼容，不要仅安装未固定版本的 `wrenai[mcp]`。
应用不会自动修改外部 Python 环境；在服务实际使用的 CLI 环境安装依赖并重启加载新代码。

应用启动的 CLI/MCP 子进程带有针对 Wren 0.15.0 MySQL 重复 LIMIT 的临时兼容修复：保留 SQL
原有排序、分页与内层限制，仅收紧最外层行数，官方 N+1 截断行为不变。资源随 JAR 打包，
不修改 Python 安装目录；直接在终端执行原版 `wren` 不会自动带上此修复。升级 Wren 后需重验并移除补丁。
工具列表在同一 MCP 实例内复用，发布或进程重建后重新发现。详见 [ADR 0072](docs/adr/0072-wren-mysql-limit-compatibility-and-discovery-cache.md)。

在 `DATAAGENT_WREN_EXECUTABLE` 对应的 Python 环境安装官方多语言记忆依赖：

```powershell
python -m pip install -r requirements-wren.txt
wren memory status
```

`memory status` 需在 Wren 工程目录执行。后端显示 `lancedb` 才是向量召回；默认使用 `paraphrase-multilingual-MiniLM-L12-v2`，首次下载需要网络。可在服务进程环境设置 `WREN_MEMORY_BACKEND=lancedb`、`WREN_EMBEDDING_BACKEND=onnx`。缺向量依赖时官方可能选择 grep；纯中文改写可能无匹配。模型不可用或索引失败会明确返回错误，不能把执行成功当作业务准确率。应用不会改动外部 Python 环境。

隔离数据、30 问参考结果、真实模型评估和浏览器复现见 [验收说明](docs/fixtures/wren-native/README.md)。测试目录中的 Python 环境不会自动替换正在运行的服务环境；部署时需要在 `DATAAGENT_WREN_EXECUTABLE` 对应环境安装上述 requirements，并重启应用加载新代码。

## Current runtime constraints

The effective rules for modeling, user-confirmed Q&A, the SQL time-window gate and query preparation are maintained in one place:

- [ARCHITECTURE_zh.md, chapter 14](ARCHITECTURE_zh.md#14-建模与问数的当前运行约束) — what the code does today
- [AGENTS.md, section 6](AGENTS.md) — the short list AI coding assistants follow
- [ADR index](docs/adr/README.md) — why, including which decisions supersede which
- [Spec index](docs/specs/README.md) — active and completed feature specs

Operational guides (deployment, single-host release, concurrency) live in [docs/guides/](docs/guides/).

## 当前工程配置 / Runtime administration

当前问数仅支持单知识库；零库为普通对话，历史多库需重新选择。全局管理员在“更多 → 大模型服务”配置 OpenAI 兼容、DeepSeek、DashScope 并人工激活；页面激活后不必重启，新轮次使用新服务，在途轮次保持原快照，无自动故障回退。application 的模型项仍为未激活页面配置时的启动源。

| 配置 | 默认与用途 |
| --- | --- |
| `dataagent.model-services.encryption-key` | `DATAAGENT_MODEL_ENCRYPTION_KEY`，空值时在实际 HOME 生成独立主密钥文件；需与平台库配套备份 |
| `dataagent.auth.password-login-enabled` | `DATAAGENT_PASSWORD_LOGIN_ENABLED=true`；prod 强制关闭密码入口 |
| `dataagent.auth.bi.enabled` / `shared-key` | `DATAAGENT_BI_LOGIN_ENABLED=false`、`DATAAGENT_BI_SHARED_KEY`；prod 启用 BI，独立于 JWT 密钥 |

MySQL Agent 上下文保存于平台库 `dataagent_agent_state`；H2 使用实际 HOME 下按数据库隔离的 JSON 文件。需同时保留工作区、附件、数据库与模型主密钥。图表按操作者私有校验。生产首次建库和升级见[单机发布手册](docs/guides/single-host-release.md)；整合边界见[ADR0114](docs/adr/0114-native-query-and-engineering-integration.md)。

Spring Boot 4 uses `spring.web.error.include-message=always` to retain actionable validation messages; the old `server.error.include-message` setting no longer applies. Error responses do not include stack traces. Read the effective upload policy from `/api/datasets/upload-policy`: the current application.yml sets 500,000 rows, 100 columns and 10,000,000 cells per file. Java fallback values do not represent the effective deployment policy.

## BI账号入口与公共知识库

`DATAAGENT_BI_ACCOUNT_LOGIN_ENABLED=false`：默认关闭，显式开启后从同源BI Local Storage读取username/account，关闭旧票据入口；这是已接受客户端身份信任前提的接入，不是可信SSO。平台角色只取数据库。浏览器路径计划 `/dify/dataagent/`，打包前设置 `DATAAGENT_BASE_PATH`。人员导入、公共库、首次DDL/已有库升级及部署验收见[BI生产接入指引](docs/integration/bi-account-production.md)。

Boot 4的JSON缓冲使用`spring.http.codecs.max-in-memory-size=50MB`，替代旧`spring.codec`名称；导入请求仍受5000条和字段长度校验。
