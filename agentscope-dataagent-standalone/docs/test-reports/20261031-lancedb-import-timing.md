# LanceDB 导入计时（2026-10-31，Asia/Shanghai）

## 范围

用户要求核对同事截图，并执行以下命令。只测模块导入，不触发建模、发布、索引、向量计算或模型调用；不修改代码、部署、数据库、SSH配置或安全软件设置。

```text
python -c "import time;t=time.time();import lancedb;print(round(time.time()-t,1))"
```

## 本机实测

- 工作目录：agentscope-dataagent-standalone；Windows。
- Python：E:\Develop\python\python.exe，3.13.3；同目录Scripts下存在wren.exe。
- 安装版本：lancedb 0.40.0、wrenai 0.15.0。
- 分支codex/integrate-boundary-lyf-v2，HEAD4af226b6；暂存为空，既有文档和原型WIP保留。
- 连续5次，每次独立Python进程；首次之前本轮未主动导入lancedb，没有重启系统或清理缓存。

| 次数 | 命令输出：导入秒数 | 进程墙钟秒数 |
| --- | ---: | ---: |
| 1 | 4.8 | 未单独测量 |
| 2 | 1.9 | 2.100 |
| 3 | 1.7 | 1.900 |
| 4 | 1.7 | 1.940 |
| 5 | 1.9 | 2.090 |

5次导入平均2.4秒，中位数1.9秒；后4次平均1.8秒。原命令计时从Python内部开始，不包含解释器启动/退出；墙钟额外包含进程与调用开销。输出精度0.1秒，不据此计算有意义的p95。

首次是本轮首测，不能称为清空系统缓存后的冷启动；独立进程仍可能受系统文件缓存影响。本次没有杀毒扫描归因证据，不据截图断言Windows杀毒导致延迟；截图的机器、版本与负载未核对，不能直接视为同环境对照。

## 66状态及复测命令

助手尝试使用已有运维账号进行非交互SSH连接，但OpenSSH在本机读取.ssh/config时报告Bad owner or permissions，未连接66，也没有修改权限、忽略配置或绕过检查。

后续用户已在66当前应用容器执行下列命令，并截图回传5次导入耗时：1.8、1.8、1.9、1.8、1.9秒，平均1.84秒。此为用户现场证据，不能写成助手远程实测；线上完整建模尚未计时。本地完整建模后续结果见[建模测速记录](20261031-modeling-speed-comparison.md)。

请在已登录的66服务器终端中执行，进入当前应用容器；不在宿主Python或分析沙箱中测试，不重新部署：

```bash
cd /data/dataagent/rehearsal-app
for i in 1 2 3 4 5; do
  printf '第%s次：' "$i"
  sudo docker compose -p dataagent66-rehearsal-app --env-file compose.env -f compose.yml \
    exec -T app python -c "import time;t=time.time();import lancedb;print(round(time.time()-t,1))"
done
```

返回值单位为秒。本次线上结果稳定在1.8–1.9秒，接近本地后四次导入水平；不同环境及首测缓存状态未统一，不能归因为杀毒或据此推算线上完整建模/索引速度。完整建模还包含模型调用、Wren命令、数据库访问等其他阶段。

## 文档验证

`mvn.cmd surefire:test -Dtest=DocsConsistencyTest -DfailIfNoTests=false`：5项通过，无失败/错误/跳过；`git diff --check`通过，仅既有换行提示；frontend、src/main/java、src/main/resources无差异。未运行无关全量测试或生产业务测试。
