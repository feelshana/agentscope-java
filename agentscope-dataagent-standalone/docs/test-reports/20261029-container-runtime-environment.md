# 2026-10-29：66运行环境安装材料与本机验收

## 范围和事实标签

用户2026-10-28已确认Java17/Python/Wren同应用容器，分析沙箱独立，用户本人Maven打包上传业务tar。实施[spec098](../specs/098-single-host-container-runtime.md)及[ADR0117](../adr/0117-single-host-container-runtime.md)。本报告是助手本机环境验收，未登录或修改66；不代表66已安装Java运行镜像或分析沙箱，更不代表真实BI/生产SQL/问答/并发验收。

Git基线codex/integrate-boundary-lyf-v2@b82bb7f4；开始前已有数据库/BI/安装文档修改，本轮保留。新增部署材料与验收尚未提交或推送。

## 已验证

- 实际构建Wren基础镜像、Java/Wren运行镜像和分析沙箱镜像。运行环境打印OpenJDK17.0.21、Python3.12.16、wrenai0.15.0；pip check通过。Docker客户端26.1.4通过Unix socket访问本机daemon29.1.3（Linux/amd64、WSL2内核6.18.54.1），与66的26.1.4/内核3.10不同。
- 原样执行docker/check-environment.sh的实际检查：无网络容器中默认ONNX模型计算2×384、finite及归一化通过；Docker访问宿主；从运行容器创建兄弟沙箱，按相同宿主绝对路径绑定shared，文件内容正确。
- 按真实沙箱约束执行：network none、1CPU/1GiB、只读根、/tmp64MiB与/workspace512MiB tmpfs、pids256。分组结果华东300/华南150、CSV往返、SciPy回归、setsid子进程、中文字符覆盖和有效PNG生成均通过。未做PNG视觉排版评审或15人负载推断。
- 字体适配：Debian Noto CJK为TTC集合；现有RunPythonTool发现SC .ttf。部署镜像从官方字体集合导出SC字体文件，保持原工具代码不变，font_manager可找到Noto Sans CJK SC且覆盖样例字符。
- 真实Docker集成11项通过（36.955秒）：依赖/daemon预检；端口4个子场景；缺配置；跨根目录；缺缓存；多个JAR；非root+socket附加组；兄弟沙箱路径；拒停不匹配标签容器；Java前台TERM/实际Wren子进程/健康HTTP；宿主控制脚本start、拒绝热替换、stop、切新目录replace与持久文件保留。
- 集成使用独立RuntimeFixture Java17小程序，只模拟生命周期、HTTP健康和真实Wren子进程；不是业务发布包，未调用Maven生成新的应用JAR。测试只停止/移除带本轮随机标签或匹配本轮专属路径的自建容器；隔离fixture文件/日志保留在Docker宿主/tmp/dataagent-runtime-test-*和本机忽略output，原有IDEA/BI等服务及其他项目容器保留。

## 验证入口

```text
docker build -f docker/runtime.Dockerfile -t dataagent/runtime:20261028 docker
docker build -f docker/sandbox-anolis79.Dockerfile -t agentscope/dataagent-sandbox:20261028 docker
DATAAGENT_CONTAINER_TESTS=1 DATAAGENT_TEST_JDK_HOME=<JDK17> python -m unittest discover -s src/test/python -p test_container_runtime.py -v
bash docker/check-environment.sh
```

最后一条在具备Unix socket/相同绝对路径和已准备模型缓存的Linux环境执行；本机通过独立Linux fixture挂载执行原脚本。默认Python测试不启动Docker，此集成显式opt-in。

原始本机记录：忽略output/66-java-wren-runtime-local-build-20261028.log、66-analysis-local-build-20261028.log、66-exact-environment-check-20261028.log、66-container-runtime-tests-20261029.log。解析依赖未逐项固定为生产锁文件；安装脚本会在66实际成功后记录该机器两镜像pip freeze和imageId，再用于后续发布固定。

最终检查：mvn spotless:check surefire:test -Dtest=DocsConsistencyTest通过（文档5项0失败/错误/跳过）；Python默认回归28项中17项通过、11项Docker默认跳过，后者已在上面的opt-in真实执行单独全部通过。所有新shell与桌面复制文本语法、assembly XML及8文件白名单存在性、git diff --check通过。未重跑不受影响的业务Java/前端全量；按用户要求未运行Maven package，也未声称实际产出了包含新ops材料的业务归档，待用户自行打包后核对。

## 交付及未完成

源码docker/提供8个白名单材料；Maven assembly仅附这些无密钥文本到ops/container，未附镜像、私有环境、真实用户Excel/SQL或业务数据。桌面“DataAgent-66环境安装材料-20261028/66环境部署-复制执行.txt”由源码逐字组装，支持SSH复制创建透明脚本，无需上传镜像包；全部清理检查容器操作在脚本内。

66接下来执行该文本后回传最后结果；成功才把正式环境安装记为完成。仍需用户打包上传、真实两库/schema/用户SQL、私有prod与密钥、端口及正式目录、健康/停止恢复、真实建模问数/附件、Nginx与BI、公司模型/搜索及并发长稳。spec098继续进行中，不移done；本轮不代用户打业务包，不升级宿主或重启Docker。

## 同日66回传更新

用户截图已确认安装脚本运行至最终成功行，显示Java17.0.21、Wren0.15.0、pip检查、Docker26.1.4客户端访问26.1.4服务端、断网向量、兄弟沙箱路径及受限分析/CSV/SciPy/中文字体/PNG/setsid均通过。因此“66环境安装检查待执行”已被该实机证据取代，前文的本机记录仍按原范围保留。未获得总耗时/镜像ID，不用本机值代填。用户明确生产两库信息尚未取得；正式目录/端口/生产配置与业务服务、BI和并发验收仍未执行。
