# spec 068：单机发布与来源回归补齐

## 背景与目标

用户已确认采用核对建议：补回 LYF 五份框架回归测试与必要忽略规则，保留当前官方 Wren 主流程及默认前端构建，吸收 LYF 的 Linux 单机发布布局。

## 方案概述

Maven 在现有 exec JAR 外生成 `dataagent-<version>.tar.gz`，含 `bin/conf/lib`、固定 Wren 依赖清单与无凭据配置样例。公共默认配置及 h2 profile 保留，私有 dev/prod 配置不进入 JAR 或发布包。Linux 脚本复用来源的进程身份、锁与持久目录设计，检查 Wren Python 依赖，等待应用健康后返回成功；停止不要求 Wren 可用。默认 Maven 仍重建前端。

## 影响面

- 构建与工具：pom、Assembly 描述、Linux 启停/Python 预检、可选 PowerShell 前端包装。
- 测试：恢复 LYF 五份测试；验证压缩包、私有配置排除、Linux 脚本失败/就绪/停止及数据保留。
- 文档：README、中英文构建说明、ARCHITECTURE 第 12 章、发布指南、来源审计及共享记忆；ADR0080。
- 不修改数据库结构、Wren 语义/召回及现有用户配置，不部署用户服务，不自动提交。

## 验收标准

1. 默认 package 重建当前前端并产出可执行 JAR 与 tar.gz；包内无私有 dev/prod 配置，公共默认值仍可用于本地启动。
2. 无 Wren 依赖或配置时启动报明确错误；停止不依赖 Wren。健康未就绪不能报告成功。
3. 重复启动、伪造/复用 PID 被拒绝；停止仅作用于本实例，持久状态不随版本目录删除。
4. 五份来源测试执行通过，Docker 用例显式启用；未验收项目如实记录。

## 关联

- [ADR0080](../../adr/0080-single-host-release-and-regression-tests.md)
