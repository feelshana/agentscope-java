# 当前任务：测试分层、慢上下文收窄与旧链路清理

更新：2026-11-12 Asia/Shanghai；分支codex/architecture-phase1，HEAD5479d152；保留阶段1全部未提交修改。

## 范围与授权

用户“按你的建议继续”授权落实全量测试盘点建议，见[spec129](../../specs/129-test-lanes-and-legacy-cleanup.md)。本地实施，不自动提交/push/合并/部署，不改远端分支保护。

## 已实现

- scripts/check.py提供后端类/方法、单元通道、前端文件/前缀和完整检查；错误选择失败。原Maven/npm默认全量保持。
- 13类integration及外部环境external标签；混合类只标外部方法，单元通道保留本地场景。AGENTS日常按改动选测、合并/发布前完整验证。
- H2种子与贡献类收窄真实JPA/文件切片，JpaPersistenceIntegrationTest保留完整装配；原12项保留。
- 清理5个旧测试类及无调用生产链路，移除TenantTablesFixture；3个条件集成测试迁当前原生MCP，CSV边界迁入QueryDataFiles。KG2类与历史数据保留。
- CI新增15项Python独立逻辑检查，完整后端/三个前缀/打包门禁保持。

## 验证状态

- Java17重点64项通过；单元通道1015项0失败/跳过，Maven42.967秒；CLI选测4项通过。前端单文件根前缀13项通过。
- 无效类/方法/文件退出2，无效前缀退出1；Python15项、报告脚本6项、actionlint通过。
- 隔离源码副本clean verify通过：后端1077项（0失败/错误、21条件跳过）、前端三前缀各105项、类型/构建/JAR/tar通过，Maven3分49秒；报告门禁通过。详见[本轮报告](../../test-reports/20261112-test-lanes-and-cleanup.md)，含163类本次耗时。
- 首轮补齐H2 JSON依赖，清理时修正接口残留及迁移编译错误后重点回归通过；未执行真实LLM/Wren/MySQL/Docker条件场景。

## 待续

实现和全量本地验证完成；最终文档5项、1174条相对链接、538个源码/入口文件与完整验证副本一致性及差异检查通过。由用户安排审阅/提交。本地代码尚未提交，GitHub CI未触发；服务未重启验收。资料同步/双BI仍待用户运行验收，产品F01–F09不自动实施。

先前盘点见[归档](CURRENT_TASK-before-test-lanes-20261112.md)，第一阶段见[阶段1报告](../../test-reports/20261112-architecture-phase1-ci-and-tests.md)。
