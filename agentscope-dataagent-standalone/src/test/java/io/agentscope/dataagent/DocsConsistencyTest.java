package io.agentscope.dataagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the AI-facing documentation structure sustainable (AGENTS.md section 7): size budgets,
 * indexed ADRs and specs, ADR supersession bookkeeping and working relative links in the documents
 * every coding agent reads. It checks structure only, not whether prose matches the code.
 */
class DocsConsistencyTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern ADR_FILE = Pattern.compile("^(\\d{4})-.+\\.md$");
    private static final Pattern STATUS =
            Pattern.compile("(?m)^#+\\s*(状态|Status)|状态\\s*[：:]|Status\\s*[：:]");
    private static final Pattern SUPERSEDES = Pattern.compile("(?m)^取代[：:]\\s*ADR-?(\\d{4})");

    @Test
    void coreDocumentsStayWithinTheirSizeBudgets() throws IOException {
        assertThat(lines("AGENTS.md")).as("AGENTS.md 行数上限 150，超出请先合并或删减").isLessThanOrEqualTo(150);
        assertThat(lines("docs/ai/CURRENT_TASK.md"))
                .as("CURRENT_TASK.md 只保留当前任务，上限 80 行")
                .isLessThanOrEqualTo(80);
        List<String> oversized = new ArrayList<>();
        String chapter = null;
        int count = 0;
        for (String line : read("ARCHITECTURE_zh.md").split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (chapter != null && count > 250) oversized.add(chapter + " (" + count + ")");
                chapter = line;
                count = 0;
            }
            count++;
        }
        if (chapter != null && count > 250) oversized.add(chapter + " (" + count + ")");
        assertThat(oversized).as("ARCHITECTURE_zh.md 单章上限 250 行").isEmpty();
    }

    @Test
    void everyAdrHasAStatusAndIsListedInTheIndex() throws IOException {
        String index = read("docs/adr/README.md");
        List<String> missingStatus = new ArrayList<>();
        List<String> notIndexed = new ArrayList<>();
        for (String name : adrFiles()) {
            if (!STATUS.matcher(read("docs/adr/" + name)).find()) missingStatus.add(name);
            if (!index.contains("(" + name + ")")) notIndexed.add(name);
        }
        assertThat(missingStatus).as("ADR 缺少状态").isEmpty();
        assertThat(notIndexed).as("ADR 未登记到 docs/adr/README.md").isEmpty();
    }

    @Test
    void supersededAdrsRecordWhoReplacedThem() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : adrFiles()) {
            String number = name.substring(0, 4);
            Matcher field = SUPERSEDES.matcher(read("docs/adr/" + name));
            while (field.find()) {
                String old = field.group(1);
                List<String> targets =
                        adrFiles().stream().filter(file -> file.startsWith(old + "-")).toList();
                if (targets.isEmpty()) {
                    problems.add(name + " 取代了不存在的 ADR-" + old);
                } else if (!read("docs/adr/" + targets.get(0))
                        .matches("(?s).*已被\\s*ADR-?" + number + "\\s*取代.*")) {
                    problems.add(targets.get(0) + " 的状态行未标注\"已被 ADR-" + number + " 取代\"");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void topLevelSpecsAreOnlyActiveOnesListedInTheIndex() throws IOException {
        String index = read("docs/specs/README.md");
        try (Stream<Path> files = Files.list(ROOT.resolve("docs/specs"))) {
            List<String> unlisted =
                    files.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(".md"))
                            .filter(
                                    name ->
                                            !name.equals("README.md")
                                                    && !name.equals("000-template.md"))
                            .filter(name -> !index.contains("(" + name + ")"))
                            .toList();
            assertThat(unlisted)
                    .as("顶层 spec 须登记在 docs/specs/README.md 的进行中列表，完成后移入 done/")
                    .isEmpty();
        }
    }

    @Test
    void documentsEveryAgentReadsHaveNoBrokenRelativeLinks() throws IOException {
        List<String> documents =
                new ArrayList<>(
                        List.of(
                                "AGENTS.md",
                                "README.md",
                                "README_zh.md",
                                "ARCHITECTURE_zh.md",
                                "docs/adr/README.md",
                                "docs/specs/README.md",
                                "docs/specs/done/README.md",
                                "docs/ai/CURRENT_TASK.md",
                                "docs/ai/PROJECT_CONTEXT.md",
                                "docs/ai/DECISIONS.md"));
        try (Stream<Path> skills = Files.walk(ROOT.resolve(".agents/skills"))) {
            skills.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .forEach(
                            path ->
                                    documents.add(
                                            ROOT.relativize(path).toString().replace('\\', '/')));
        }
        List<String> broken = new ArrayList<>();
        for (String document : documents) {
            Path base = ROOT.resolve(document).getParent();
            Matcher link = LINK.matcher(read(document));
            while (link.find()) {
                String target = link.group(1);
                if (target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*|^#.*|^/.*")) continue;
                String file = target.split("#", 2)[0];
                if (!file.isEmpty() && !Files.exists(base.resolve(file).normalize()))
                    broken.add(document + " -> " + target);
            }
        }
        assertThat(read("CLAUDE.md").strip()).as("CLAUDE.md 引用 AGENTS.md").isEqualTo("@AGENTS.md");
        assertThat(broken).as("失效的相对链接").isEmpty();
    }

    private static List<String> adrFiles() throws IOException {
        try (Stream<Path> files = Files.list(ROOT.resolve("docs/adr"))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> ADR_FILE.matcher(name).matches() && !name.startsWith("0000-"))
                    .sorted()
                    .toList();
        }
    }

    private static int lines(String relative) throws IOException {
        return read(relative).split("\n", -1).length;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
