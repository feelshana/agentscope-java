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
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure-function tests for the application-layer keyword evidence retrieval. */
class KnowledgeEvidenceTest {

    private static final String SHORT_KPI_DOC =
            "# 2026年9月考核口径\n- 日活跃用户：目标 1000 万/日（全国去重）\n- 付费用户：目标 200 万/日";

    @Test
    void shortDocStaysWholeWithLeadingTitle() {
        List<KnowledgeEvidence.Chunk> chunks = KnowledgeEvidence.chunks(SHORT_KPI_DOC);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).title()).isEqualTo("2026年9月考核口径");
        assertThat(chunks.get(0).text()).contains("目标 1000 万");
    }

    @Test
    void shortDocBoundaryFollowsNormalisedLength() {
        assertThat(KnowledgeEvidence.isShortDoc(SHORT_KPI_DOC)).isTrue();
        assertThat(KnowledgeEvidence.isShortDoc(null)).isFalse();
        assertThat(KnowledgeEvidence.isShortDoc("   ")).isFalse();
        assertThat(KnowledgeEvidence.isShortDoc("甲".repeat(KnowledgeEvidence.WHOLE_DOC_CHARS)))
                .isTrue();
        assertThat(KnowledgeEvidence.isShortDoc("甲".repeat(KnowledgeEvidence.WHOLE_DOC_CHARS + 1)))
                .isFalse();
        // \r\n collapses to \n before measuring: raw length 800, normalised exactly 600
        String crlf = "考核\r\n".repeat(KnowledgeEvidence.WHOLE_DOC_CHARS / 3);
        assertThat(KnowledgeEvidence.isShortDoc(crlf)).isTrue();
    }

    @Test
    void markdownDocSplitsAtHeadings() {
        StringBuilder doc = new StringBuilder("# 2026年9月咪咕视频考核口径\n\n");
        doc.append("日活跃用户目标为 1000 万，统计口径为全国去重MAU。\n\n".repeat(20));
        doc.append("## 付费用户口径\n\n");
        doc.append("付费用户目标为 200 万，按省拆分考核。\n\n".repeat(20));

        List<KnowledgeEvidence.Chunk> chunks = KnowledgeEvidence.chunks(doc.toString());

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).title()).isEqualTo("2026年9月咪咕视频考核口径");
        assertThat(chunks.get(0).text()).contains("日活跃用户目标");
        assertThat(chunks).anySatisfy(c -> assertThat(c.title()).isEqualTo("付费用户口径"));
    }

    @Test
    void headinglessDocSplitsOnBlankLines() {
        String paraA = "第一段：活跃用户的统计说明。".repeat(40);
        String paraB = "第二段：付费用户的统计说明。".repeat(40);
        List<KnowledgeEvidence.Chunk> chunks = KnowledgeEvidence.chunks(paraA + "\n\n" + paraB);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).text()).startsWith("第一段");
        assertThat(chunks.get(chunks.size() - 1).text()).contains("第二段");
    }

    @Test
    void oversizedSectionIsHardSplit() {
        String doc = "# 长节\n\n" + "超长正文行，包含很多汉字。".repeat(120);

        List<KnowledgeEvidence.Chunk> chunks = KnowledgeEvidence.chunks(doc);

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(
                c ->
                        assertThat(c.text().length())
                                .isLessThanOrEqualTo(KnowledgeEvidence.MAX_CHUNK_CHARS));
        chunks.forEach(c -> assertThat(c.title()).isEqualTo("长节"));
    }

    @Test
    void termsMixAsciiTokensAndCjkBigrams() {
        Set<String> terms = KnowledgeEvidence.terms("咪咕视频考核 DAU 2000万");

        assertThat(terms).contains("咪咕", "视频", "考核", "dau", "2000");
        assertThat(terms).doesNotContain("d", "2", "a");
    }

    @Test
    void singleCjkCharStaysUnigram() {
        assertThat(KnowledgeEvidence.terms("量")).containsExactly("量");
    }

    @Test
    void scoreWeightsBodyAndTitleHits() {
        Set<String> terms = Set.of("考核", "目标", "无关词");
        KnowledgeEvidence.Chunk chunk = new KnowledgeEvidence.Chunk("考核口径", "考核目标值为 1000 万，按日统计。");

        int score = KnowledgeEvidence.score(chunk, terms);

        // body hits 考核+目标 (2*2), title hits 考核 (+3)
        assertThat(score).isEqualTo(7);
        assertThat(KnowledgeEvidence.score(new KnowledgeEvidence.Chunk(null, "完全不相关的内容"), terms))
                .isZero();
    }

    @Test
    void blankInputsAreRejected() {
        assertThat(KnowledgeEvidence.chunks("   ")).isEmpty();
        assertThat(KnowledgeEvidence.chunks(null)).isEmpty();
        assertThat(KnowledgeEvidence.terms("  ")).isEmpty();
        assertThat(KnowledgeEvidence.terms(null)).isEmpty();
    }
}
