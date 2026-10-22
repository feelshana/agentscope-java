# 2026-10-20 发布服务启动装配修复

## 根因与修复

用户提供的是Spring启动BeanCreationException，MdlPublishService新增事件发布构造器与原有权限/删除保护构造器同时标记必选Autowired，Java允许这些重载，所以编译与直接new的业务测试都通过，真实Spring却不能选择构造器。之前编译/IDE检查通过不等于服务已能启动。

当前只有一个Autowired完整构造器，同时接收KnowledgeBaseOperations、KnowledgeBaseAccessService、ApplicationEventPublisher；原有测试构造器保持兼容且不参与自动注入。不能只删除旧权限构造器或切换到无事件构造器，否则会丢失平台保护或发布预准备事件。

## 回归证据

新增MdlPublishWiringTest通过真实AnnotationConfigApplicationContext注册服务并创建Bean，核对权限、删除保护与事件发布器均被注入。依赖使用测试替身，不连接外部数据库或启动生产服务。

- 旧编译产物复现用户同一条Invalid autowire-marked constructor，target/mdl-wiring-red-retry.log。
- 修复后Spring真实Bean创建成功，target/mdl-wiring-green-retry.log。
- 初次测试源码注册ObjectMapper的构造器引用歧义已改成显式lambda；一次构建后测试遇到class资源文件缺失，无代码改动重跑通过。保留mdl-wiring-red.log及mdl-wiring-green.log，不把失败轮记为通过。
- 最终mvn spotless:check package通过，699项0失败0错误19条件跳过，包含前端npm run build和打包（target/mdl-wiring-package.log）。IDE指定服务和新测试构建也成功无ERROR。

IDE静态检查当前服务无错误。这里验证的是用户报错Bean的真实Spring装配，不宣称已重启完整业务服务；需加载新制品重启。未改数据库、运行配置、历史工程，未提交Git。
