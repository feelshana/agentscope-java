package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlExampleDocumentTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "sql: >-\n  SELECT 1",
                "sql: |\n  SELECT 1",
                "sql: SELECT 1",
                "sql: \"SELECT 1\"",
                "sql: 'SELECT 1'"
            })
    void structuredEditPreservesExactValuesAndOtherFields(String declaration) {
        String body = "\n# 说明\n保留说明\n```sql\nSELECT 1\n```\n\n备注原样保留。\n";
        var doc =
                SqlExampleDocument.parse(
                        "---\nnl: 原问题\n"
                                + declaration
                                + "\ntags:\n- test\nextra:\n  enabled: true\n---\n"
                                + body);
        String sql = "SELECT 'a  b: # \\\" 中文' AS v, '```' AS fence\n-- 保留行注释\nFROM 商品";
        String question = "问题: 包含 #、\"引号\"和\\路径\n第二行";
        for (int i = 0; i < 3; i++) {
            String updated = doc.update(question, sql);
            doc = SqlExampleDocument.parse(updated);
            assertThat(doc.question()).isEqualTo(question);
            assertThat(doc.sql()).isEqualTo(sql);
            assertThat(updated)
                    .contains(
                            "enabled: true",
                            "- test",
                            "保留说明",
                            "备注原样保留。",
                            "````sql\n" + sql + "\n````");
        }
    }

    @Test
    void foldedWithoutTagsKeepsClosingDelimiterOnItsOwnLine() {
        String updated =
                SqlExampleDocument.parse("---\nnl: 问题\nsql: >-\n  SELECT 1\n---\n")
                        .update("新问题", "SELECT 2");
        assertThat(updated).contains("\n---\n");
        assertThat(SqlExampleDocument.parse(updated).sql()).isEqualTo("SELECT 2");
    }

    @Test
    void crlfAndLiteralNewlinesNeverFlattenSql() {
        var doc =
                SqlExampleDocument.parse(
                        "\uFEFF---\r\nnl: 旧问题\r\nsql: SELECT 1\r\n---\r\n正文原样\r\n");
        String sql = "SELECT 'a  b' AS v\r\n-- 一行注释\r\nFROM 商品\r\n";
        String updated = doc.update("新问题", sql);
        assertThat(SqlExampleDocument.parse(updated).sql()).isEqualTo(sql);
        assertThat(updated).endsWith("正文原样\r\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "---\nnl: x\nsql: SELECT 1---\n",
                "---\nnl: x\nsql: [1]\n---\n",
                "---\nnl: x\nnl: y\nsql: SELECT 1\n---\n",
                "---\nnl: x\nsql: !!java.lang.String x\n---\n",
                "# missing header",
                "---\nnl: x\nsql: SELECT 1tags:\n- test\n---\n"
            })
    void invalidDocumentsFailBeforeWriting(String text) {
        assertThatThrownBy(() -> SqlExampleDocument.parse(text))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void unrelatedMarkdownCodeIsNotModified() {
        String body =
                "# 提示\n```python\nprint(1)\n```\n```sql\nSELECT 1\n```\n```sql\nSELECT 99\n```\n";
        String updated =
                SqlExampleDocument.parse("---\nnl: q\nsql: SELECT 1\n---\n" + body)
                        .update("q2", "SELECT 2");
        assertThat(updated)
                .endsWith(body.replace("```sql\nSELECT 1\n```", "```sql\nSELECT 2\n```"));
    }
}
