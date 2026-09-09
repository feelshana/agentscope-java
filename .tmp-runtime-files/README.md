# 子代理（Subagents）

本目录中每个 `*.md` 文件定义一个可供父智能体委派任务的子代理。文件名（不含扩展名）
就是子代理 id；frontmatter 声明其系统提示词与工具白名单。

示例骨架：

```markdown
---
name: researcher
description: 针对一个问题开展调查并返回书面总结。
tools: [read_file, grep_files, glob_files]
---

你是一名调研专员。专注于收到的任务本身；不要直接编辑文件。
```

添加真实子代理后请删除本 README。
