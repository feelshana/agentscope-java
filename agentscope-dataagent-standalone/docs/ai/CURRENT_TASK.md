# 当前任务：合并同事新增能力（2026-10-30 Asia/Shanghai）

## 起点与授权
- 当前分支 codex/integrate-boundary-lyf-v2；起点 122d2b7e，合入同事 dfa71bef（共同祖先 ec6cb3d8）。用户授权无歧义部分直接合并；不推送、不操作65/66。
- 用户确认：术语改存每库 Wren Naming conventions，保留旧表/数据，暂不迁移测试术语；接受成功查询自动生成会话CSV及下载链接。
- 原六份未提交部署记录已核对保存并保留；现场部署检查点见[归档](archive/20261030-deployment-checkpoint-before-merge.md)。同事原检查点见[归档](archive/20261030-colleague-checkpoint-before-merge.md)。

## 整合范围
- 同事新增 SQL 编辑/验证、工程内术语、停用 caveats、查询CSV、归因/异常/预测技能、推荐问题。
- 保留已有管理员手动模型切换与轮次快照、持久会话、BI账号、公共库与只读浏览、操作用户图表归属、对话界面设计及源码构建部署能力。
- 冲突按功能合并，前端产物由合并源码重建；修正分析请求参数在聊天入口丢失的问题。
- ADR/spec 编号冲突改为新增唯一编号；首次DDL增加推荐问题两字段，升级前须检查/补列，不删旧术语表。

## 验证与状态
- 本轮源码冲突解决与验证已完成；提交状态以Git HEAD/MERGE_HEAD为准，未推送。Java993/0失败/0错误/32跳过，前端三路径各53；Spotless/文档门禁/构建、断网沙箱分析脚本与MySQL新建/升级实测通过，详见[报告](../test-reports/20261030-colleague-merge-validation.md)。
- 条件跳过不作通过：其中4项宿主分析脚本测试已用真实沙箱补验，其余Wren/模型等未启用；真实LLM端到端、正确率、10–15并发与生产环境未验。
- 图表新参数路径保留操作用户归属；只读SQL编辑器禁用格式化，术语读在锁内复核权限，参数未确认拒绝Python；入口分析字段原本已有会话上下文通路，本次补齐请求字段，不称原按钮必然失效。
- 66仍运行原已启动演练版本，Healthy/health UP为此前用户截图事实；未加载本次代码。正式两库待取得，管理员/模型/真实建模问答待验。
- 65候选配置 /data/dify/docker/nginx/conf.d/default.conf 及Compose映射已定位；当前账号无写权限和Docker权限，等待有权限同事核对实际挂载后配置/检查/reload。BI必须同origin保留Local Storage。
- 正式Java并发阻塞尚未修，async2仅旧output实验，不纳入本次合并。
