# develop-2.0整合个人UI（2026-11-01）

## 授权与起点

用户确认已push个人分支，授权切到develop-2.0合入liyufan_dev并检查同事分支；主线push由用户执行，66部署尚未操作。

只读远程查询并fetch三个明确分支，核对：

| 引用 | 本轮远程提交 |
| --- | --- |
| develop-2.0 | 10bde6040a62a09f76de9cc83b798f2c695f6b7c |
| liyufan_dev | 0fbc0faef2f3cc10a94439108314c611554052c2 |
| wangchao_dev | a85ccec61a3fa05717d506c1afe60aa4e1c31d25 |

起点工作区/暂存为空，个人本地与远程一致。本地主线原5b1079a6，先快进到origin/develop-2.0，再以--no-ff合入个人分支，保留明确合并记录。无冲突，未stash/reset或丢弃提交，无新spec/ADR。

## 同事分支包含关系

git merge-base --is-ancestor origin/wangchao_dev origin/develop-2.0返回0；主线与同事分支的left/right计数6/0，同事没有主线未包含的提交。其后续31365282配色与2623052b语义工作台改造已在10bde604合并主线，不因个人远程分支较早而重复合并。

个人分支与主线left/right为3/0（个人多3条）。本轮合并保留b1a71a20、d6ad3ba3和0fbc0fae；其中d6ad3ba3已完成上一轮个人/同事功能冲突整合。

## 验证与边界

- 自动合并结果最初与liyufan_dev全树一致；本轮随后仅新增整合报告与共享记录。
- frontend、src、pom与个人分支无差异；后端Java、测试、application.yml及迁移目录与原远程主线也无差异。
- 本轮npm test：64项×3访问base通过；格式/文档一致性与暂存检查在合并提交前执行，最终结果以SESSION_LOG和Git回读为准。
- 沿用个人分支已经构建验证的生产资源：index-BtJyAZ5G.js/index-DFxa2lLP.css/ontology-C7BOwsdv.js，没有重新生成相同资源或另改业务代码。
- 不重复浏览器和无关后端全量测试、Java打包、真实LLM/上传/建模/发布；既有专项证据见20261101-style-harmony、card-metadata、composer-colors报告。
- 本轮没有新增SQL迁移或应用配置改动；66当前运行状态和是否完成历史升级仍以现场为准，不能把本地合并当作生产部署成功。

## 用户下一步

本地merge提交完成后，用户将develop-2.0推送到origin；提交号见本次git log回读。66更新应fetch主线并核对该提交，再按[更新与重启指引](../guides/66-update-operations.md)构建/升级现有应用，使用当次新镜像标签；不重新初始化数据库或导入已持久化示例。助手本轮不push、不登录或更改66。
