# 语义建模系统设计方案（参考 WrenAI）

> 版本: v1.0 | 日期: 2026-09-15 | 状态: 草案

---

## 一、背景与动机

### 1.1 当前问题

data-agent 现有的问数流程中，LLM 直接生成 SQL 文本，存在以下问题：

| 问题 | 表现 |
|------|------|
| 聚合方式错误 | 用 `COUNT(*)` 代替 `SUM(visit_count)`，用行数代替 PV |
| 忘记去重 | 计算用户数时不使用 `COUNT(DISTINCT account)` |
| JOIN 条件错误 | 猜测列名或写错关联条件 |
| 业务规则丢失 | 不知道"推广排除外协"等约束 |
| 无法积累学习 | 每次问数从零开始，不记忆历史成功查询 |

### 1.2 WrenAI 的核心启发

WrenAI 是一个开源 GenBI 引擎，其核心设计理念：

1. **语义层（MDL）**：用 JSON 定义 Model（逻辑表）、Relationship（JOIN条件）、Cube（预聚合指标），LLM 只需引用业务名称，不需要知道物理表结构
2. **上下文策略**：小 schema 返回全文（≤30K字符），大 schema 用向量搜索召回相关片段
3. **Cube 结构化查询**：LLM 输出结构化 JSON 参数（measures/dimensions/filters），后端转化为 SQL，避免 LLM 手写 SQL 的出错率
4. **查询历史记忆**：自动存储成功的 NL→SQL 对，下次问数时召回作为 few-shot 示例
5. **Seed Queries**：从 MDL 自动生成基础 NL-SQL 对，解决冷启动问题

### 1.3 设计目标

- 支持两种语义建模入口：上传 ontology.json 文件 + 从数据表自动建模
- 语义模型在知识图谱中可视化展示（替换原 GraphRAG 图谱逻辑）
- 问数时 LLM 输出结构化 JSON 参数，后端转化为可执行 SQL
- 支持查询历史记忆，越用越准

---

## 二、总体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                          data-agent 语义建模系统                      │
│                                                                      │
│  ┌─────────────┐   ┌─────────────┐   ┌──────────────────────────┐   │
│  │ 入口1:       │   │ 入口2:       │   │   SemanticModelService  │   │
│  │ 上传         │   │ 数据表       │   │   (统一语义模型管理)     │   │
│  │ ontology.json│   │ 自动建模     │──▶│                         │   │
│  └──────┬──────┘   └──────┬──────┘   │  ┌─────────────────┐    │   │
│         │                 │          │  │ SemanticModel    │    │   │
│         └────────┬────────┘          │  │ (统一模型对象)    │    │   │
│                  │                   │  └────────┬────────┘    │   │
│                  ▼                   └───────────┼─────────────┘   │
│  ┌──────────────────────────────┐               │                  │
│  │     知识图谱可视化展示         │◄──────────────┤                  │
│  │  SemanticGraphView           │               │                  │
│  │  (替换原 KnowledgeGraphView)  │               ▼                  │
│  └──────────────────────────────┘    ┌──────────────────────┐      │
│                                      │   问数流程改造         │      │
│                                      │   LLM → 结构化JSON   │      │
│                                      │   → SQL生成器 → 执行  │      │
│                                      └──────────────────────┘      │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.1 与现有系统的关系

```
                    现有系统                    改造后
                    ────────                    ──────
  本体模型      OntologyModel (YAML)     →   SemanticModel (JSON)
  上传通道      POST /api/ontology/upload →   POST /api/semantic-model/upload
  图谱展示      KnowledgeGraphView        →   SemanticGraphView
                (GraphRAG语义图谱)             (语义模型图谱)
  问数工具      run_sql_preview            →   execute_cube_query
                (LLM直接写SQL)                (LLM输出JSON,后端转SQL)
  持久化        OntologyEntity            →   SemanticModelEntity
```

---

## 三、语义模型数据定义

### 3.1 统一语义模型（SemanticModel）

参考 WrenAI 的 MDL（Modeling Definition Language），设计统一的语义模型。
顶层包含 6 个部分：

```
SemanticModel
├── models[]          — 逻辑表定义（对应物理表，但用业务名称引用）
├── relationships[]   — 表间关系（JOIN 条件预定义）
├── cubes[]           — 预聚合指标（measures + dimensions + timeDimensions）
├── rules[]           — 业务规则（如"活跃用户=近30天有访问"）
├── limitations[]     — 数据局限说明（如"点击数据只覆盖7天"）
└── 元信息             — name, description, catalog, schema, dataSource
```

### 3.2 逻辑表（SemanticModelTable）— 对应 WrenAI Model

```java
public class SemanticModelTable {
    private String name;              // 逻辑名（Agent引用的名字，如 "click_observation"）
    private String tableName;         // 物理表名（如 "ds_click_observation"）
    private String primaryKey;        // 主键列
    private String label;             // 中文标签（如 "点击观察"）
    private String description;       // 业务描述（含聚合注意事项）
    private String kind;              // "dimension"（维度表）| "fact"（事实表）
    private List<SemanticColumn> columns;
}
```

**SemanticColumn**：

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 列名 |
| type | String | 数据类型（VARCHAR, INTEGER, TIMESTAMP...） |
| label | String | 中文标签 |
| description | String | 业务描述（含聚合注意事项） |
| notNull | boolean | 是否非空 |
| expression | String | 计算列表达式（如 `organization.member_count`） |
| isCalculated | boolean | 是否计算列 |
| relationship | String | 引用的关系名（如 `clickPerformedBy`） |

### 3.3 关系（SemanticRelationship）

```java
public class SemanticRelationship {
    private String name;              // "clickPerformedBy"
    private List<String> models;      // ["click_observation", "user_object"]
    private String joinType;          // MANY_TO_ONE | ONE_TO_MANY | MANY_TO_MANY
    private String condition;         // "click_observation.account = user_object.account"
    private String label;             // "点击由用户产生"
    private String description;
}
```

**设计要点**：关系预定义了 JOIN 条件，LLM 写查询时不需要知道如何 JOIN，
只需引用 model 名称，后端根据 relationship 自动展开 JOIN。

### 3.4 Cube 预聚合指标 — 核心概念

Cube 是 WrenAI 最重要的设计——它将**聚合逻辑预定义**，LLM 只需选择 Cube 中的
measure 和 dimension，不需要自己写 SUM/COUNT/GROUP BY。

```java
public class SemanticCube {
    private String name;              // "click_metrics"
    private String baseObject;        // "click_observation"（基础模型名）
    private String label;             // "点击指标"
    private String description;

    private List<CubeMeasure> measures;           // 度量指标
    private List<CubeDimension> dimensions;       // 维度
    private List<CubeTimeDimension> timeDimensions; // 时间维度
}
```

**CubeMeasure**（度量）：

| 字段 | 示例 | 说明 |
|------|------|------|
| name | `click_pv` | 度量名（LLM引用的标识） |
| expression | `SUM(visit_count)` | SQL 聚合表达式（后端生成SQL用） |
| type | `BIGINT` | 返回值类型 |
| description | `点击PV。对已聚合列求和，不能用COUNT(*)` | 描述（含常见错误警告） |

**CubeDimension**（维度）：

| 字段 | 示例 | 说明 |
|------|------|------|
| name | `module_name` | 维度名 |
| expression | `module_name` | SQL 表达式 |
| type | `VARCHAR` | 数据类型 |
| description | `模块` | 描述 |

**CubeTimeDimension**（时间维度）：

| 字段 | 示例 | 说明 |
|------|------|------|
| name | `click_time` | 时间维度名 |
| expression | `click_time` | SQL 表达式 |
| type | `TIMESTAMP` | 数据类型 |
| description | `点击时间` | 描述 |

### 3.5 业务规则和数据局限

```java
public class SemanticRule {
    private String id;                    // "R1"
    private String name;                  // "活跃用户定义"
    private String description;           // "近30天有访问行为的用户"
    private Map<String, Object> parameters; // {"window_days": 30}
    private String sqlHint;              // "WHERE click_time >= CURRENT_DATE - INTERVAL '30 days'"
}

public class SemanticLimitation {
    private String id;                    // "click_window"
    private String name;                  // "点击数据时间范围"
    private String description;           // "实际记录只出现在9月1日到7日"
}
```

### 3.6 ontology.json 文件格式

完整示例（兼容 WrenAI MDL 格式）：

```json
{
    "$schema": "https://agentscope.io/semantic-model/v1.json",
    "layoutVersion": 1,
    "catalog": "migu_ops",
    "schema": "public",
    "description": "分析云运营语义模型 — 从 migu-ops-v1 语义模型转换",
    "dataSource": "MYSQL",
    "models": [
        {
            "name": "user_object",
            "tableName": "ds_user_object",
            "primaryKey": "user_id",
            "label": "用户",
            "description": "分析云用户。身份键是用户ID。",
            "kind": "dimension",
            "columns": [
                {"name": "user_id", "type": "VARCHAR", "notNull": true, "label": "用户ID"},
                {"name": "account", "type": "VARCHAR", "label": "账号"},
                {"name": "company", "type": "VARCHAR", "label": "公司"},
                {"name": "department", "type": "VARCHAR", "label": "部门"},
                {"name": "position", "type": "VARCHAR", "label": "职位",
                 "description": "只有显式标记为领导的行才是领导；空值是未知，不推断。"}
            ]
        },
        {
            "name": "click_observation",
            "tableName": "ds_click_observation",
            "label": "点击观察",
            "description": "模块点击事件。访问次数列已聚合过，PV必须对该列求和，不能用行数代替。",
            "kind": "fact",
            "columns": [
                {"name": "account", "type": "VARCHAR", "label": "账号"},
                {"name": "module_name", "type": "VARCHAR", "label": "模块"},
                {"name": "project_name", "type": "VARCHAR", "label": "项目名称"},
                {"name": "visit_count", "type": "INTEGER", "label": "访问次数",
                 "description": "源列已聚合，PV对它求和；用行数代替是错的。"},
                {"name": "click_time", "type": "TIMESTAMP", "label": "点击时间"},
                {"name": "click_user", "type": "user_object", "relationship": "clickPerformedBy",
                 "label": "点击用户(handle)"},
                {"name": "user_company", "type": "VARCHAR", "isCalculated": true,
                 "expression": "click_user.company", "label": "用户公司"}
            ]
        }
    ],
    "relationships": [
        {
            "name": "clickPerformedBy",
            "models": ["click_observation", "user_object"],
            "joinType": "MANY_TO_ONE",
            "condition": "click_observation.account = user_object.account",
            "label": "点击由用户产生"
        }
    ],
    "cubes": [
        {
            "name": "click_metrics",
            "baseObject": "click_observation",
            "label": "点击指标",
            "description": "模块点击事件聚合指标。PV必须对visit_count求和，不能用行数代替。",
            "measures": [
                {"name": "click_pv", "expression": "SUM(visit_count)", "type": "BIGINT",
                 "description": "点击PV。对已聚合的访问次数列求和，用行数代替是错的。"},
                {"name": "click_uv", "expression": "COUNT(DISTINCT account)", "type": "BIGINT",
                 "description": "点击UV。按用户身份去重。"},
                {"name": "click_row_count", "expression": "COUNT(*)", "type": "BIGINT",
                 "description": "点击观察行数（非PV，仅供排查用）。"}
            ],
            "dimensions": [
                {"name": "account", "expression": "account", "type": "VARCHAR", "description": "账号"},
                {"name": "module_name", "expression": "module_name", "type": "VARCHAR", "description": "模块"},
                {"name": "project_name", "expression": "project_name", "type": "VARCHAR", "description": "项目名称"},
                {"name": "event_company", "expression": "event_company", "type": "VARCHAR", "description": "事件记录公司"}
            ],
            "timeDimensions": [
                {"name": "click_time", "expression": "click_time", "type": "TIMESTAMP", "description": "点击时间"}
            ]
        }
    ],
    "rules": [
        {"id": "R1", "name": "活跃用户定义", "description": "活跃用户是近30天内有访问行为的用户。",
         "parameters": {"window_days": 30}},
        {"id": "R4", "name": "用户去重规则", "description": "凡计算用户数都要去重。",
         "sqlHint": "使用 COUNT(DISTINCT account)"},
        {"id": "R5", "name": "推广对象排除规则", "description": "推广对象排除外协和数智化部。",
         "sqlHint": "WHERE account NOT LIKE '%wx' AND department != '数智化部'"}
    ],
    "limitations": [
        {"id": "click_window", "name": "点击数据时间范围",
         "description": "导出条件写的是8月9日到9月7日，实际记录只出现在9月1日到7日。"},
        {"id": "cross_source_pv", "name": "跨来源访问次数不可加",
         "description": "点击和报表访问没有共同事件ID，两个来源的访问次数不能相加。"}
    ]
}
```

---

## 四、入口1：上传 ontology.json 进行语义建模

### 4.1 API 设计

```
POST /api/semantic-model/upload
Content-Type: multipart/form-data
Body: file (ontology.json)
Response: {
    "groupId": "abc123",
    "modelCount": 10,
    "relationshipCount": 11,
    "cubeCount": 7,
    "ruleCount": 5
}
```

### 4.2 处理流程

```
用户上传 ontology.json
    │
    ▼ SemanticModelParser.parse(jsonText)
    Jackson ObjectMapper 反序列化为 SemanticModel 对象
    │
    ▼ 校验（SemanticModelValidator）
    ├── 验证 model 引用的物理表是否存在（通过 DataSourceRegistry 查表）
    ├── 验证 relationship 引用的两端 model 是否存在
    ├── 验证 cube 引用的 baseObject 是否存在
    ├── 验证 cube measure 的 expression 语法合法性
    └── 验证 JOIN condition 的列名是否匹配 model 的 columns
    │
    ▼ 计算 MDL Hash（SHA256 前16位，用于缓存失效检测）
    │
    ▼ SemanticModelService.save(groupId, semanticModel, origin="uploaded")
    持久化到 SemanticModelEntity 表（UPSERT，同 group 只保留一份）
    │
    ▼ 前端自动刷新 SemanticGraphView
```

### 4.3 向后兼容

现有的 `OntologyModel`（model.yaml）通过转换器自动映射为 `SemanticModel`：

| OntologyModel 字段 | SemanticModel 字段 | 转换规则 |
|-------------------|-------------------|---------|
| objects → OntologyObject | models → SemanticModelTable | label→label, table→tableName, kind→kind |
| objects → ObjectProperty | models → SemanticColumn | name→name, type→type |
| relationships → OntologyRelationship | relationships → SemanticRelationship | from/to→models[], join→condition |
| metrics → OntologyMetric | cubes → CubeMeasure | aggregate+column→expression |
| rules → OntologyRule | rules → SemanticRule | 直接映射 |
| limitations → OntologyLimitation | limitations → SemanticLimitation | 直接映射 |

---

## 五、入口2：根据知识库数据表自动语义建模

### 5.1 API 设计

```
POST /api/semantic-model/auto-generate
Content-Type: application/json
Body: {
    "groupId": "abc123",
    "tableNames": ["ds_click_observation", "ds_user_object", "ds_project"]
}
Response: {
    "modelCount": 3,
    "inferredRelationships": 2,
    "generatedCubes": 2
}
```

### 5.2 处理流程

```
用户选择数据表
    │
    ▼ 获取表结构
    JdbcSchemaInspector 读取 INFORMATION_SCHEMA.COLUMNS
    得到每张表的：列名、类型、是否主键、是否可空
    │
    ▼ SemanticModelBuilder.build(tables)
    │
    ├── 5.2.1 每个表 → SemanticModelTable
    │   ├── 数值列（INTEGER/DOUBLE/DECIMAL）标记为度量候选
    │   ├── 字符串列（VARCHAR/CHAR）标记为维度候选
    │   └── 时间列（TIMESTAMP/DATE/DATETIME）标记为时间维度
    │
    ├── 5.2.2 推断关系（RelationInferenceService）
    │   ├── 列名精确匹配：A.account = B.account → MANY_TO_ONE
    │   ├── 外键命名模式：A.project_id = B.project_id → MANY_TO_ONE
    │   ├── ID后缀约定：*_id → 可能的外键引用
    │   └── 生成 SemanticRelationship 列表
    │
    └── 5.2.3 自动生成 Cube
        ├── 每个事实表（kind=fact）自动生成基础 Cube
        │   ├── measures:
        │   │   ├── SUM(数值列) — 排除主键、外键、*_id 列
        │   │   ├── COUNT(*) — 行数统计（仅供排查）
        │   │   └── COUNT(DISTINCT 身份列) — UV统计
        │   ├── dimensions: 所有字符串列
        │   └── timeDimensions: 所有时间列
        └── 命名规则: {tableName}_metrics
    │
    ▼ 用户确认/调整后保存
    SemanticModelService.save(groupId, model, origin="auto-generated")
```

### 5.3 智能过滤规则（参考 WrenAI seed_queries.py）

自动生成 Cube 时，以下列**不应**作为度量（measure）目标：

| 排除规则 | 原因 | 示例 |
|---------|------|------|
| 主键列 | `SUM(user_id)` 无业务意义 | `project_id` |
| 关系键列 | 出现在 JOIN 条件中的列 | `account`（在 clickPerformedBy 中） |
| `*_id` 后缀 | 通常是标识符，不是度量 | `user_id`, `org_id` |
| 计算列 | 已经派生，不应再聚合 | `user_company` |

---

## 六、知识图谱可视化展示

### 6.1 替换策略

```
原逻辑：
  ontologyOrigin === 'uploaded' → OntologyGraphView（本体图谱）
  其他                          → KnowledgeGraphView（GraphRAG语义图谱）

新逻辑：
  始终展示 SemanticGraphView（语义模型图谱）
  数据源统一来自 SemanticModelService
```

### 6.2 渲染方案

使用 AntV G6（与现有组件一致），渲染三层结构：

```
┌─────────────────────────────────────────────────────────────────┐
│                   SemanticGraphView 渲染结构                     │
│                                                                 │
│  Layer 1: Model 节点（矩形卡片，按 kind 区分颜色）                │
│                                                                  │
│  ┌─────────────────┐   ┌─────────────────┐                      │
│  │ 📋 user_object   │   │ 📊 click_obs     │                     │
│  │ [维度表]          │   │ [事实表]          │                     │
│  │                  │   │                  │                      │
│  │ • user_id (PK)   │   │ • account        │                     │
│  │ • account        │   │ • module_name    │                     │
│  │ • company        │   │ • visit_count    │                     │
│  │ • department     │   │ • click_time     │                     │
│  │ • position       │   │                  │                     │
│  └────────┬─────────┘   └────────┬─────────┘                     │
│           │                      │                               │
│  Layer 2: Relationship 边（带标签的有向箭头）                      │
│           │   clickPerformedBy   │                               │
│           │   (MANY_TO_ONE)      │                               │
│           └──────────────────────┘                               │
│                                                                  │
│  Layer 3: Cube 节点（圆角矩形，独立颜色）                          │
│  ┌────────────────────┐   ┌────────────────────┐                 │
│  │ 🎯 click_metrics    │   │ 🎯 capacity_metrics │                │
│  │ ────────────────── │   │ ────────────────── │                │
│  │ Σ click_pv         │   │ Σ used_tb          │                │
│  │ Σ click_uv         │   │ MAX file_ratio     │                │
│  │ ────────────────── │   │ ────────────────── │                │
│  │ 维度: module, acct │   │ 维度: project_id   │                │
│  │ 时间: click_time   │   │ 时间: record_date  │                │
│  └────────────────────┘   └────────────────────┘                │
└─────────────────────────────────────────────────────────────────┘
```

### 6.3 API 设计

```
GET /api/semantic-model/{groupId}/graph
Response: {
    "nodes": [
        {
            "id": "user_object",
            "type": "model",
            "label": "用户",
            "kind": "dimension",
            "description": "分析云用户",
            "columns": [
                {"name": "user_id", "type": "VARCHAR", "label": "用户ID", "primaryKey": true},
                {"name": "account", "type": "VARCHAR", "label": "账号"}
            ]
        },
        {
            "id": "click_metrics",
            "type": "cube",
            "label": "点击指标",
            "baseObject": "click_observation",
            "measures": [
                {"name": "click_pv", "expression": "SUM(visit_count)", "type": "BIGINT"}
            ],
            "dimensions": [
                {"name": "module_name", "type": "VARCHAR"}
            ],
            "timeDimensions": [
                {"name": "click_time", "type": "TIMESTAMP"}
            ]
        }
    ],
    "edges": [
        {
            "id": "clickPerformedBy",
            "source": "click_observation",
            "target": "user_object",
            "type": "relationship",
            "label": "clickPerformedBy",
            "joinType": "MANY_TO_ONE",
            "condition": "click_observation.account = user_object.account"
        },
        {
            "id": "cube-click_metrics",
            "source": "click_metrics",
            "target": "click_observation",
            "type": "cube-base",
            "label": "baseObject"
        }
    ]
}
```

### 6.4 交互功能

| 功能 | 说明 |
|------|------|
| 点击 Model 节点 | 展开侧边栏：列清单（名称、类型、描述、是否计算列） |
| 点击 Cube 节点 | 展开侧边栏：measures/dimensions/timeDimensions 详情 |
| 点击 Relationship 边 | 弹出 tooltip：JOIN 条件、类型、描述 |
| 搜索 | 按名称/标签搜索并高亮节点 |
| 缩放/拖拽 | 标准 G6 交互（zoom-canvas, drag-canvas, drag-element） |
| 统计面板 | 展示 Model 数 / Relationship 数 / Cube 数 / 总列数 |

---

## 七、问数流程改造（核心）

### 7.1 当前流程 vs 改造后

```
当前流程:
  用户提问 → Agent 调用 describe_ontology 获取本体 → LLM 直接写 SQL → run_sql_preview 执行
  ❌ LLM 容易写错聚合方式、JOIN条件、忘记去重

改造后流程:
  用户提问 → Agent 获取语义上下文 + 召回历史查询
          → LLM 输出结构化 JSON 参数
          → 后端 CubeQueryToSqlConverter 转为可执行 SQL
          → 执行 → 回答 → 存储成功查询对
  ✅ LLM 只需选择 cube/measure/dimension，聚合逻辑由系统保证
```

### 7.2 LLM 输出的结构化 JSON 格式

LLM 根据问题类型选择输出两种格式之一：

#### 格式A：Cube 查询（推荐，适用于指标聚合类问题）

```json
{
    "type": "cube_query",
    "cube": "click_metrics",
    "measures": ["click_pv", "click_uv"],
    "dimensions": ["module_name", "event_company"],
    "timeDimensions": [
        {
            "name": "click_time",
            "granularity": "month",
            "start": "2026-08-01",
            "end": "2026-09-07"
        }
    ],
    "filters": [
        {"dimension": "event_company", "operator": "eq", "value": "中国移动"}
    ],
    "orderBy": [{"member": "click_pv", "direction": "desc"}],
    "limit": 100
}
```

#### 格式B：SQL 查询（适用于明细查询、子查询等非聚合场景）

```json
{
    "type": "sql_query",
    "sql": "SELECT account, module_name, visit_count FROM click_observation WHERE visit_count > 10 ORDER BY visit_count DESC",
    "models_used": ["click_observation"],
    "limit": 100
}
```

### 7.3 Cube 查询 → SQL 转换（CubeQueryToSqlConverter）

这是整个方案的核心组件，将结构化 JSON 转为可执行 SQL：

```
输入: CubeQuery JSON
    │
    ▼ 1. 查找 Cube 定义
    cube = semanticModel.cubes.find(name="click_metrics")
    baseTable = semanticModel.models.find(name=cube.baseObject)
    │
    ▼ 2. 构建 SELECT 列
    维度列:   module_name AS module_name, event_company AS event_company
    时间维度: DATE_FORMAT(click_time, '%Y-%m') AS click_time
    度量列:   SUM(visit_count) AS click_pv, COUNT(DISTINCT account) AS click_uv
    │
    ▼ 3. 构建 FROM
    FROM ds_click_observation    ← 物理表名（来自 baseTable.tableName）
    │
    ▼ 4. 构建 WHERE
    WHERE event_company = '中国移动'           ← filters
      AND click_time >= '2026-08-01'           ← timeDimensions.start
      AND click_time < '2026-09-07'            ← timeDimensions.end
    │
    ▼ 5. 构建 GROUP BY
    GROUP BY module_name, event_company, DATE_FORMAT(click_time, '%Y-%m')
    │
    ▼ 6. 构建 ORDER BY + LIMIT
    ORDER BY click_pv DESC
    LIMIT 100
    │
    ▼ 输出可执行 SQL:

SELECT
    module_name AS module_name,
    event_company AS event_company,
    DATE_FORMAT(click_time, '%Y-%m') AS click_time,
    SUM(visit_count) AS click_pv,
    COUNT(DISTINCT account) AS click_uv
FROM ds_click_observation
WHERE event_company = '中国移动'
  AND click_time >= '2026-08-01'
  AND click_time < '2026-09-07'
GROUP BY module_name, event_company, DATE_FORMAT(click_time, '%Y-%m')
ORDER BY click_pv DESC
LIMIT 100
```

#### 时间粒度转换规则

| granularity | SQL 表达式（MySQL） | 示例输出 |
|-------------|-------------------|---------|
| day | `DATE(expr)` | `2026-09-01` |
| week | `DATE(DATE_SUB(expr, INTERVAL WEEKDAY(expr) DAY))` | `2026-08-25` |
| month | `DATE_FORMAT(expr, '%Y-%m')` | `2026-09` |
| quarter | `CONCAT(YEAR(expr), '-Q', QUARTER(expr))` | `2026-Q3` |
| year | `YEAR(expr)` | `2026` |

#### Filter 操作符

| operator | SQL 转换 | 示例 |
|----------|---------|------|
| eq | `= value` | `event_company = '中国移动'` |
| ne | `!= value` | `event_company != '数智化部'` |
| gt | `> value` | `visit_count > 10` |
| gte | `>= value` | `visit_count >= 5` |
| lt | `< value` | `visit_count < 5` |
| lte | `<= value` | `visit_count <= 100` |
| in | `IN (v1, v2, ...)` | `module_name IN ('报表', '仪表盘')` |
| not_in | `NOT IN (...)` | ... |
| like | `LIKE '%value%'` | `account LIKE '%wx'` |
| between | `BETWEEN v1 AND v2` | `click_time BETWEEN '2026-08-01' AND '2026-09-07'` |

### 7.4 Agent 工具改造

新增和改造 DataAgentToolkit 中的工具：

| 工具名 | 类型 | 说明 |
|-------|------|------|
| `get_semantic_context` | 新增 | 获取语义模型上下文（schema + 规则），参考 WrenAI get_context |
| `recall_similar_queries` | 新增 | 召回相似历史查询对作为 few-shot，参考 WrenAI recall_queries |
| `execute_cube_query` | 新增 | 执行结构化 Cube 查询（JSON → SQL → 执行） |
| `run_sql_preview` | 保留 | 执行只读 SQL（用于非聚合类复杂查询） |
| `store_query` | 新增 | 存储成功查询对（学习记忆） |
| `describe_ontology` | 废弃 | 被 get_semantic_context 替代 |
| `search_model` | 废弃 | 被 get_semantic_context 替代 |
| `get_model` | 废弃 | 被 get_semantic_context 替代 |

#### get_semantic_context 的混合策略

参考 WrenAI 的 `SCHEMA_DESCRIBE_THRESHOLD = 30000`：

```java
public String getSemanticContext(String question) {
    SemanticModel model = semanticService.getModel(groupId);
    String fullText = SemanticSchemaDescriber.describe(model);

    if (fullText.length() <= SCHEMA_THRESHOLD) {
        // 小模型：返回完整 schema 文本 + 业务规则
        return fullText + "\n\n## 业务规则\n" + formatRules(model.getRules());
    } else {
        // 大模型：根据问题进行关键词匹配，返回相关片段
        return searchRelevantSchema(model, question, limit=5);
    }
}
```

### 7.5 系统提示词改造

AGENTS.md 增加结构化查询工作流：

```markdown
## 问数工作流

当用户提出数据分析问题时，按以下步骤执行：

1. **获取上下文**：调用 get_semantic_context(question)
   了解可用的表、Cube指标和业务规则

2. **召回历史**：调用 recall_similar_queries(question)
   查找相似的历史查询作为参考模板

3. **构建查询**：根据问题类型选择输出格式：
   - 指标聚合类（"各模块PV"、"本月用户数"）→ cube_query JSON
   - 明细查询（"查看某用户的点击记录"）→ sql_query JSON

4. **执行查询**：
   - cube_query → 调用 execute_cube_query(json)
   - sql_query → 调用 run_sql_preview(sql)

5. **回答用户**：用自然语言总结查询结果

6. **存储学习**：调用 store_query(question, json) 保存成功的查询对

### cube_query JSON 格式

{
    "type": "cube_query",
    "cube": "<Cube名称>",
    "measures": ["<度量名>"],
    "dimensions": ["<维度名>"],
    "timeDimensions": [
        {"name": "<时间维度>", "granularity": "day|week|month|quarter|year",
         "start": "YYYY-MM-DD", "end": "YYYY-MM-DD"}
    ],
    "filters": [
        {"dimension": "<维度>", "operator": "eq|ne|gt|gte|lt|lte|in|not_in",
         "value": "..."}
    ],
    "orderBy": [{"member": "<度量或维度>", "direction": "asc|desc"}],
    "limit": 100
}

### 重要约束

- 聚合查询**必须**使用 Cube 中预定义的 measures，不要自己写 SUM/COUNT
- 涉及用户数的查询**必须**使用含 DISTINCT 的 measure（如 click_uv）
- 遵守 rules 中的业务规则（如 R5 推广排除规则）
- 注意 limitations 中的数据局限（如时间范围、跨来源不可加）
- 如果问题不涉及聚合指标（如查看明细），使用 sql_query 格式
```

---

## 八、查询历史记忆（学习机制）

### 8.1 Seed Queries 自动生成

参考 WrenAI 的 `seed_queries.py`，从 SemanticModel 自动生成初始 NL-SQL 对：

| 模板 | NL 示例 | SQL 示例 |
|------|---------|---------|
| 基本列出 | "列出所有 click_observation" | `SELECT * FROM click_observation LIMIT 100` |
| 简单聚合 | "click_observation 的总 visit_count" | `SELECT SUM(visit_count) FROM click_observation` |
| 分组聚合 | "按 module_name 统计 visit_count" | `SELECT module_name, SUM(visit_count) FROM click_observation GROUP BY 1` |
| JOIN 示例 | "click_observation 关联 user_object" | `SELECT * FROM click_observation JOIN user_object ON ...` |

### 8.2 查询历史存储

```sql
CREATE TABLE dataagent_query_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id VARCHAR(64) NOT NULL,
    nl_query TEXT NOT NULL,
    query_json TEXT NOT NULL,
    sql_generated TEXT,
    source VARCHAR(32) DEFAULT 'user',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_group (group_id),
    INDEX idx_source (source)
);
```

`source` 取值：`seed`（自动生成）、`user`（用户问数成功后存储）

### 8.3 召回策略

当前阶段使用**关键词匹配**（LIKE 全文搜索），后续可升级为向量相似度搜索：

```java
public List<QueryHistoryRecord> recallSimilarQueries(String question, int limit) {
    // Phase 1: 关键词匹配
    String[] keywords = extractKeywords(question);
    return queryHistoryRepository.findByKeywords(groupId, keywords, limit);

    // Phase 2（后续升级）: 向量相似度搜索
    // EmbeddingService.embed(question) → float[] vector
    // queryHistoryRepository.findBySimilarVector(vector, topK=5)
}
```

### 8.4 完整问数流程时序图

```
用户: "上个月各模块的PV和UV是多少？"
    │
    ▼ 1. Agent 调用 get_semantic_context("上个月各模块的PV和UV")
    返回: SemanticModel 的 schema 描述（tables + cubes + rules）
    LLM 知道了: click_metrics cube 有 click_pv 和 click_uv measures
    │
    ▼ 2. Agent 调用 recall_similar_queries("上个月各模块的PV和UV")
    返回: [
      {nl: "本月各模块点击量", sql: "SELECT module_name, SUM(visit_count) ..."},
      {nl: "9月模块访问PV", sql: "SELECT module_name, SUM(visit_count) ..."}
    ]
    │
    ▼ 3. Agent 调用 execute_cube_query()
    参数: {
        "type": "cube_query",
        "cube": "click_metrics",
        "measures": ["click_pv", "click_uv"],
        "dimensions": ["module_name"],
        "timeDimensions": [
            {"name": "click_time", "granularity": "month",
             "start": "2026-08-01", "end": "2026-09-07"}
        ]
    }
    │
    ▼ 4. CubeQueryToSqlConverter 生成 SQL
    SELECT module_name, SUM(visit_count) AS click_pv, COUNT(DISTINCT account) AS click_uv
    FROM ds_click_observation
    WHERE click_time >= '2026-08-01' AND click_time < '2026-09-07'
    GROUP BY module_name
    │
    ▼ 5. JdbcSqlConnector 执行 SQL → 返回结果集
    │
    ▼ 6. Agent 用自然语言总结结果回答用户
    │
    ▼ 7. Agent 调用 store_query() 保存成功查询对
    nl: "上个月各模块的PV和UV是多少？"
    json: {"type": "cube_query", "cube": "click_metrics", ...}
```

---

## 九、数据持久化

### 9.1 semantic_model 表（替代/补充 dataagent_ontology）

```sql
CREATE TABLE dataagent_semantic_model (
    id VARCHAR(64) PRIMARY KEY,
    group_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    mdl_json LONGTEXT NOT NULL COMMENT '完整 SemanticModel JSON',
    mdl_hash VARCHAR(32) COMMENT 'MDL内容的SHA256前16位',
    origin VARCHAR(32) NOT NULL DEFAULT 'auto-generated'
        COMMENT 'uploaded | auto-generated',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_group (group_id),
    INDEX idx_owner (owner_id),
    INDEX idx_origin (origin)
);
```

### 9.2 query_history 表

```sql
CREATE TABLE dataagent_query_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id VARCHAR(64) NOT NULL,
    nl_query TEXT NOT NULL,
    query_json TEXT NOT NULL COMMENT '结构化查询JSON（cube_query或sql_query）',
    sql_generated TEXT COMMENT '生成的可执行SQL',
    source VARCHAR(32) DEFAULT 'user' COMMENT 'seed | user',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_group (group_id),
    INDEX idx_source (source)
);
```

### 9.3 Entity 与 Repository

| Java 类 | 说明 |
|---------|------|
| `SemanticModelEntity` | JPA Entity，映射 `dataagent_semantic_model` 表 |
| `SemanticModelRepository` | JpaRepository，提供 findByGroupId/findByOwnerId 等查询 |
| `QueryHistoryEntity` | JPA Entity，映射 `dataagent_query_history` 表 |
| `QueryHistoryRepository` | JpaRepository，提供关键词搜索和向量搜索 |

---

## 十、Java 包结构

```
io.agentscope.dataagent.semantic
├── model/
│   ├── SemanticModel.java              // 顶层语义模型
│   ├── SemanticModelTable.java         // 逻辑表
│   ├── SemanticColumn.java             // 列定义
│   ├── SemanticRelationship.java       // 关系
│   ├── SemanticCube.java               // Cube（预聚合指标）
│   ├── CubeMeasure.java                // 度量
│   ├── CubeDimension.java              // 维度
│   ├── CubeTimeDimension.java          // 时间维度
│   ├── SemanticRule.java               // 业务规则
│   ├── SemanticLimitation.java         // 数据局限
│   └── SemanticMetadata.java           // 元数据（catalog/schema/dataSource）
├── parser/
│   ├── SemanticModelParser.java        // JSON 解析
│   └── SemanticModelValidator.java     // 校验
├── service/
│   ├── SemanticModelService.java       // 核心服务（CRUD + 图谱数据）
│   ├── CubeQueryToSqlConverter.java    // Cube JSON → SQL 转换
│   ├── SemanticSchemaDescriber.java    // Schema 文本描述生成（注入 prompt）
│   └── QueryHistoryService.java        // 查询历史管理
├── builder/
│   ├── SemanticModelBuilder.java       // 表结构自动建模
│   └── RelationInferenceService.java   // 关系推断
├── converter/
│   └── OntologyToSemanticModelConverter.java  // OntologyModel → SemanticModel
├── web/
│   ├── api/
│   │   └── SemanticModelController.java    // REST API
│   └── persistence/
│       ├── jpa/
│       │   ├── SemanticModelEntity.java
│       │   ├── SemanticModelRepository.java
│       │   ├── QueryHistoryEntity.java
│       │   └── QueryHistoryRepository.java
│       └── SemanticModelPersistentStore.java
└── graph/
    └── SemanticGraphDataTransformer.java  // SemanticModel → GraphView 数据
```

---

## 十一、与现有 ontology-agent-workbench 的关系

当前项目中已存在 `ontology-agent-workbench` 相关设计思路（本体驱动的 NL2SQL 编译框架），
本方案与其互补关系如下：

| 维度 | ontology-agent-workbench | 本方案（语义建模系统） |
|------|-------------------------|---------------------|
| 定位 | 独立的编译框架原型 | data-agent 内置模块 |
| 模型格式 | model.yaml（OWL/RDF） | SemanticModel JSON（参考 WrenAI MDL） |
| SQL 生成 | 编译器（DuckDB） | CubeQueryToSqlConverter + CTE 重写 |
| 知识图谱 | Semantica OWL | AntV G6 可视化 |
| LLM 交互 | LLM 输出 SQL 文本 | LLM 输出结构化 JSON |
| 学习机制 | 无 | 查询历史记忆 + Seed Queries |

两套体系可以后续融合：ontology-agent-workbench 的编译能力可以作为 `sql_query` 类型查询
的后端引擎（处理复杂子查询和 CTE），而 Cube 查询走 `CubeQueryToSqlConverter`。

---

## 十二、实施阶段

### Phase 1：基础框架（预计 3 天）

| 步骤 | 内容 | 产出 |
|------|------|------|
| 1.1 | 定义 SemanticModel 数据模型类 | 11 个 Java POJO |
| 1.2 | 实现 SemanticModelParser + Validator | JSON 解析与校验 |
| 1.3 | 实现 Entity + Repository 持久化 | 数据库表 + JPA 层 |
| 1.4 | 实现 SemanticModelService | CRUD + 图谱数据 API |
| 1.5 | 实现 SemanticModelController | REST API 端点 |
| 1.6 | 实现 OntologyToSemanticModelConverter | 向后兼容 |
| 1.7 | 前端 SemanticGraphView | AntV G6 图谱渲染 |
| 1.8 | 前端 API 集成 + 页面集成 | OntologyDetailPage 改造 |

### Phase 2：自动建模（预计 2 天）

| 步骤 | 内容 | 产出 |
|------|------|------|
| 2.1 | SemanticModelBuilder | 表结构 → SemanticModel |
| 2.2 | RelationInferenceService | 关系推断 + Cube 自动生成 |

### Phase 3：问数改造（预计 4 天）

| 步骤 | 内容 | 产出 |
|------|------|------|
| 3.1 | CubeQueryToSqlConverter | JSON → SQL 核心转换 |
| 3.2 | DataAgentToolkit 新增工具 | 5 个新工具 |
| 3.3 | AGENTS.md 系统提示词改造 | 结构化查询工作流 |
| 3.4 | QueryHistoryService | 学习记忆 + Seed Queries |

### Phase 4：测试与优化（预计 2 天）

| 步骤 | 内容 | 产出 |
|------|------|------|
| 4.1 | 单元测试 | Parser / Validator / Converter 测试 |
| 4.2 | 集成测试 | 上传 → 图谱 → 问数 → 回答 |
| 4.3 | 性能优化 | Schema 阈值调优 |

---

## 十三、风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LLM 不熟悉 cube_query JSON 格式 | 输出错误参数 | 提示词中提供充分示例 + few-shot 历史查询 |
| 自动建模推断的关系不准确 | Cube 的 JOIN 条件错误 | 自动建模结果需用户确认后保存 |
| Cube 预定义的度量不覆盖所有场景 | 部分问题无法用 cube_query 回答 | 保留 sql_query 格式作为兜底 |
| 大 schema 上下文超限 | Token 超限 | 混合策略（阈值判断 + 关键词搜索） |
| 查询历史积累不够 | 冷启动效果差 | Seed Queries 自动生成基础示例 |
