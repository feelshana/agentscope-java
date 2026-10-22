AgentScope HarnessAgent 多用户隔离与 Wren MCP 多 Project 架构
结论：AgentScope 支持你的场景。不同的 Wren project 可以对应不同的 MCP Client，而且可以同时服务多个用户、多个 group_id。
但有一个重要区别：
HarnessAgent 的会话隔离，不等于 MCP Client 的 Project 隔离。
你不能仅依靠 RuntimeContext.userId 和 sessionId，就认为连接 Wren 的 MCP Client 自动切换了项目。需要在业务层增加 group_id → published project → MCP Client 的绑定机制。
我核对了 AgentScope Java 2.0 的 HarnessAgent 文档和 MCP 集成代码。下面按 Java 版 HarnessAgent 设计；如果你用的是 Python AgentScope，实现接口会有差别。
一、HarnessAgent 是怎么隔离多个用户的？
AgentScope Java 2.0 将 HarnessAgent 设计为封装 ReActAgent 的运行层，包含 Session、Workspace、Memory、Skill、Sandbox、MCP 等能力。
它主要通过两个标识隔离对话：
- userId：用户身份。
- sessionId：会话身份。
其状态存储以用户和会话身份作为隔离维度。最新版还区分原生会话历史、工作区文件系统和沙箱的隔离范围。

GitHub
+1



HarnessAgent / Shared Builder
共享 Model、Prompt、Skill 配置


用户 A
userId = A
sessionId = S1
独立对话历史

userId = A
sessionId = S2
另一段对话



用户 B
userId = B
sessionId = S3
独立对话历史

userId = B
sessionId = S4
另一段对话





1.1 RuntimeContext 的作用
典型调用方式：
RuntimeContext ctx = RuntimeContext.builder()
    .userId("user-A")
    .sessionId("session-001")
    .build();

agent.call(message, ctx).block();


当不同用户、不同会话调用时，HarnessAgent 根据身份读取对应的会话状态。
同一个会话后续调用，可以恢复原来的对话历史。
1.2 同一个 HarnessAgent 能并发处理多个用户吗？
可以。
但这里需要区分 AgentScope Java 的实例生命周期模式：
模式	行为	适用情况
共享 HarnessAgent 实例	多会话可以并发，由 RuntimeContext 区分状态	工具配置固定、可安全共享
共享 Builder，每次请求创建 Agent	每个请求独立装配 Tool、MCP、Workspace	不同 Project、动态工具配置
每个会话维护独立 Agent	Agent 与会话长期绑定	长任务、持续执行状态
AgentScope 当前的使用文档推荐：共享 Builder，每次直接请求构建 Agent，在请求结束后关闭 Agent。持久化历史让新的 Agent 实例仍可以恢复原会话。共享实例也是支持的模式，但需要满足相应的并发和资源安全条件。

GitHub
+1



对于你的 Wren 问数系统，我更倾向于第二种模式，尤其是不同 group_id 会装配不同 MCP Client 的情况下。
二、不同 Wren Project 能不能使用不同 MCP Client？
完全可以。
AgentScope 的 MCP 集成支持一个 Toolkit 注册多个 MCP Client，也支持每个 Agent 装配自己的 MCP Client。
Java 版提供 McpClientBuilder 和 McpClientWrapper，可通过 STDIO 或 HTTP 连接 MCP Server。

GitHub



例如你现在的业务配置：
group_id	Wren project	MCP Client
999	/wren/projects/sales	Client-Sales
1000	/wren/projects/finance	Client-Finance
1001	/wren/projects/orders	Client-Orders
如果 Wren MCP Server 在启动时通过参数 --project 绑定项目，那么每个 Client 就可以连接一个独立的 Wren MCP Server 进程。
示意：
McpClientWrapper salesClient =
    McpClientBuilder.create("wren-sales")
        .stdioTransport(
            "wren",
            "mcp",
            "--project",
            "/wren/projects/sales"
        )
        .build();

McpClientWrapper financeClient =
    McpClientBuilder.create("wren-finance")
        .stdioTransport(
            "wren",
            "mcp",
            "--project",
            "/wren/projects/finance"
        )
        .build();


这里的 wren mcp --project ... 是用于说明参数绑定的示意命令，并不是已确认适用于你当前 Wren 版本的启动语法。实际命令应以已安装版本的 MCP Server 启动帮助为准。
关键不是具体命令，而是：
MCP Client 构建时固定 Project 参数，不在处理不同用户请求时修改共享 Client 的 Project。
三、我建议的架构：HarnessAgent + Project MCP Client Pool
你的场景有两个不同的隔离维度：
- 用户维度：谁在问、属于哪个会话。
- 知识库维度：这次问题属于哪个 group_id、哪个已发布 Wren Project。
两者不能混为一谈。
问数 API
userId · sessionId · group_id · question


Knowledge Router
鉴权 + 解析 Published Release


HarnessAgent Factory
按请求装配 Agent、Skill、Toolkit

Agent A
用户 A / Session 1
group 999


Agent B
用户 B / Session 2
group 1000





Wren MCP Client Pool
Key = group_id + release_id
Client 999
Project: sales
Release: 105


Client 1000
Project: finance
Release: 208





Wren Server A
Published Sales MDL


Wren Server B
Published Finance MDL




同一个 group_id，是否要为每个用户启动 MCP Client？
不需要。
例如 100 个用户同时查询 group_id=999：
user-A ── session-1 ──┐
user-B ── session-2 ──┼── Wren MCP Client (group 999)
user-C ── session-3 ──┤          │
                     │          ▼
user-N ── session-N ──┘    Published Project 105


多个用户可以共享同一个只读 MCP Server，但必须满足以下条件：
1. Wren MCP Server 对工具调用是并发安全的。
2. Server 不使用会被不同请求修改的全局 Project、Profile 或会话工作目录。
3. 用户的数据行列权限能够在数据源侧正确强制执行。
4. MCP 的响应结果不会写入其他用户可见的共享会话状态。
这里的共享是架构上可行，并不是说 AgentScope 会自动验证 Wren MCP 的线程安全性。
如果 Wren MCP Server 内部存在可变的会话状态，或者不同用户拥有不同数据库凭据，就应该进一步拆分客户端池，必要时每个安全主体使用独立进程。
四、Project MCP Client Pool 应该如何实现？
核心不是 Map<groupId, MCPClient>，而是：
record ProjectKey(
    String groupId,
    String releaseId
) {}


为什么要加 releaseId？
因为你只使用已发布版本，但新发布发生时，旧请求可能还在运行。
例如：
group=999, release=105 → Client A

发布 release=106

group=999, release=106 → Client B


Client A 等原请求完成后再关闭。
新请求进入 Client B。
这样就不会出现一个正在执行的 Agent 因为发布切换而查到另一套 Schema 的问题。
推荐的职责划分
interface PublishedProjectResolver {
    PublishedProject resolve(
        String userId,
        String groupId
    );
}

interface WrenMcpClientPool {
    McpClientLease acquire(
        PublishedProject project
    );
}

interface HarnessAgentFactory {
    HarnessAgent create(
        McpClientLease lease
    );
}


这三个接口是建议你在项目中定义的业务抽象，不是 AgentScope 官方提供的同名接口。
McpClientLease 应实现 AutoCloseable，关闭 Lease 时只释放本次请求的引用，而不是无条件关闭共享 MCP Client。
请求处理伪代码
public Mono<Msg> ask(AskRequest request) {

    PublishedProject published =
        resolver.resolve(
            request.userId(),
            request.groupId()
        );

    RuntimeContext ctx = RuntimeContext.builder()
        .userId(request.userId())
        .sessionId(request.sessionId())
        .build();

    return Mono.usingWhen(
        clientPool.acquireAsync(published),

        lease -> {
            HarnessAgent agent =
                agentFactory.create(lease);

            return agent.call(
                request.toMsg(),
                ctx
            ).doFinally(signal ->
                agent.close()
            );
        },

        lease -> lease.releaseAsync()
    );
}


这是表达资源生命周期的架构伪代码，并非可直接复制编译的实现。实际 Toolkit 装配、McpClientWrapper 连接与异步释放方式，需要根据你使用的 AgentScope Java 版本实现。
尤其要注意：不要让 agent.close() 意外销毁 MCP Pool 中仍被其他 Agent 使用的 Client。Tool 和 Client 的资源归属要单独定义。
五、HarnessAgent 的隔离范围该如何配置？
AgentScope Java 的 IsolationScope 主要控制 Workspace / Filesystem 等状态的共享范围。当前文档中：
IsolationScope	含义	你的问数场景
SESSION	各会话独立	推荐
USER	同一用户共享工作区	需要额外处理跨 group 的长期记忆
AGENT	Agent 下各用户共享	不建议用于租户私有知识
GLOBAL	全局共享	不建议
注意：IsolationScope 并不是 MCP Server 的数据源隔离开关。MCP Project 必须由路由层独立控制。

GitHub
+1



对你来说，还需要解决一个比 userId/sessionId 更容易忽视的问题：
同一个用户在不同 group_id 之间切换，可能造成会话上下文污染。
例如：
userId = A
sessionId = 001

第一轮：
group_id = 999
问题：2025 年销售收入

第二轮：
group_id = 1000
问题：这些客户的订单数量


即使 MCP Client 已经切换到了 1000，Agent 仍可能根据第一轮对话理解“这些客户”，把前一个知识库的查询结果带入当前问题。
因此我建议：
一个会话固定绑定一个 group_id，而不只是固定 userId 和 sessionId。
会话元数据：
{
  "userId": "A",
  "sessionId": "001",
  "groupId": "999",
  "releasePolicy": "LATEST_PUBLISHED",
  "status": "ACTIVE"
}


当用户切换知识库时，创建新的会话。每次请求都必须验证会话绑定的 group_id 与授权范围一致。
如果你希望一个会话支持多知识库联合查询，就应单独设计跨知识库查询机制，不能让 Agent 任意切换客户端。
六、最终应该选择哪种实现？
两种部署策略对比
实现方式	优点与适用情况
每个请求新建 MCP Client	隔离简单，但每次可能需要启动 Wren 进程、初始化连接，延迟和资源消耗较高。适合初期验证。
按 Project 缓存 MCP Client	同一发布项目复用连接，适合正式多用户系统；需要自己管理连接池、并发和进程生命周期。

我建议你采用第二种：
共享 HarnessAgent 配置 + 每请求构建 Agent + 按发布 Project 复用 MCP Client。
对于 Wren MCP，我还建议核实一个关键点：它是否把 project 固定在 MCP Server 启动参数上，还是会在每次 Tool 调用时接收 Project 参数。如果是前者，你的 Project Client Pool 方案最直接；如果是后者，则需要检查服务端是否存在可变全局状态，不能直接假设单 Client 多项目并发安全。
参考官方实现：
- AgentScope Harness 架构
- AgentScope MCP Client 与 Toolkit
- Workspace 与 IsolationScope
这里还有一个值得继续确认的设计选择： 你之前已经有 Wren MCP Tool 调用链，如果现在改为官方 Wren MCP Client 接入 HarnessAgent，就不一定需要再自行实现 CLI Executor。可以保留官方 Skill，由 MCP 提供执行能力。
不过，这种组合必须验证 Skill 所指导的 CLI 工作流能否被 MCP Tool 等价承载；否则就会重复出现你之前日志中的 Skill 指导一套流程、MCP 实际提供另一套工具的情况。
