/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the LangSmith-Fleet-style workspace folder layout that the builder UI edits: {@code
 * AGENTS.md}, {@code tools.json}, an example skill, and {@code subagents/}/{@code memory/}
 * directories. Existing files are left untouched, so calling this on a populated workspace is a
 * no-op for whatever is already there.
 *
 * <p>The templates are intentionally opinionated — they teach a new builder user the workspace
 * conventions without requiring them to read external docs first.
 */
public final class WorkspaceScaffolder {

    private WorkspaceScaffolder() {}

    /**
     * Materializes the workspace folder for an agent. Safe to call repeatedly: only files that do
     * not already exist are created.
     *
     * @param workspace target workspace directory (will be created if missing)
     * @param displayName human-readable agent name used in the generated AGENTS.md heading
     * @param sysPrompt optional system-prompt body included in AGENTS.md (may be {@code null})
     */
    public static void scaffold(Path workspace, String displayName, String sysPrompt)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd(displayName, sysPrompt));
        writeIfMissing(workspace.resolve("tools.json"), toolsJson());
        writeIfMissing(
                workspace.resolve("skills").resolve("example-skill").resolve("SKILL.md"),
                exampleSkillMd());
        writeIfMissing(workspace.resolve("subagents").resolve("README.md"), subagentsReadme());
        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String agentsMd(String displayName, String sysPrompt) {
        String name = (displayName == null || displayName.isBlank()) ? "agent" : displayName;
        String prompt =
                (sysPrompt == null || sysPrompt.isBlank()) ? "你是一个乐于助人的智能体助手。" : sysPrompt.trim();
        return """
        # %s

        %s

        ## 这个目录如何工作

        这个目录*就是*智能体本身。你放在这里的任何内容都会在运行时被自动加载——没有需要单独同步的配置。

        - **`AGENTS.md`** —— 本文件。直接在此编辑系统提示词与行为规则；下一次会话即生效。
        - **`tools.json`** —— 声明该智能体可调用的内建工具。可查看生成文件中可用的工具 id 及 `allow` / `deny` 示例。
        - **`skills/`** —— 每个子目录是一个技能：智能体可按名称调用的 Markdown 操作手册。内置了入门示例 `example-skill/SKILL.md`。
        - **`subagents/`** —— 用于委派工作的子代理定义。见 `subagents/README.md`。
        - **`memory/`** —— 由运行时管理的长期记忆存储；通常无需手工编辑。

        ## 撰写建议

        - 让本提示词聚焦于*智能体做什么*与*该如何表现*。示例、schema、一次性指令放进技能里。
        - 修改工具或技能后，进行中的会话保留旧配置直到重置；新会话立即使用新配置。
        """
                .formatted(name, prompt);
    }

    private static String toolsJson() {
        return """
        {
          "// description": "Builder tools allowlist for this agent. Remove this file to allow all tools.",
          "// available":   ["read_file", "write_file", "edit_file", "list_files", "grep_files", "glob_files", "execute", "memory_search", "memory_get", "session_search", "session_list", "session_history"],
          "allow": [
            "read_file",
            "write_file",
            "edit_file",
            "list_files",
            "grep_files",
            "glob_files"
          ],
          "deny": []
        }
        """;
    }

    private static String exampleSkillMd() {
        return """
        ---
        name: example-skill
        description: 入门技能模板。在正式依赖它之前，请把正文替换为你自己的操作手册；这个示例只是为了演示 workspace 的目录结构。
        ---

        # 示例技能

        技能是一段可被智能体按名称调用的操作手册。目录名（`example-skill`）就是技能 id。

        ## 何时使用

        描述智能体应当使用该技能的场景。要写得具体——运行时会在智能体挑选技能时把这一节反馈给它。

        ## 步骤

        1. 说明该技能需要的输入。
        2. 描述要做的工作——读哪些文件、写哪些内容、总结什么。
        3. 说明期望的输出格式。

        编写完你自己的技能后请删除本文件。
        """;
    }

    private static String subagentsReadme() {
        return """
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
        """;
    }
}
