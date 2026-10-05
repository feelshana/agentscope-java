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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword-level evidence retrieval over the plain-text knowledge documents stored per knowledge
 * base ({@code DatasetKnowledgeEntity}). Application-layer mini-RAG: no embedding model, no
 * external service — the docs are short (capped at 20k chars per KB) and structured (markdown
 * headings), so heading-aware chunking plus CJK-bigram term matching is enough to locate the
 * passage that answers a question. The framework-level {@code io.agentscope.core.rag} package is
 * deprecated for removal ("integrate retrieval at the application layer"), which is exactly what
 * this class is.
 *
 * <p>Four pure functions:
 *
 * <ul>
 *   <li>{@link #chunks(String)} — splits one document into passages: whole doc when short,
 *       markdown-heading sections otherwise, blank-line paragraphs as a fallback, hard-split at
 *       {@value #MAX_CHUNK_CHARS} chars.
 *   <li>{@link #isShortDoc(String)} — whether the doc is small enough that callers inject it
 *       whole instead of keyword-filtering it (compact KPI/target specs).
 *   <li>{@link #terms(String)} — query normalisation: lower-cased ASCII/digit tokens (2+) plus
 *       CJK bigrams (a single CJK char stays a unigram), so no Chinese segmenter is needed.
 *   <li>{@link #score(Chunk, Set)} — 2 points per term found in the body, +3 more when the term
 *       hits the section title (headings like "考核口径" are high signal).
 * </ul>
 */
final class KnowledgeEvidence {

    /**
     * Docs at or below this size are returned as one chunk and injected whole by the caller
     * without keyword filtering — typical KPI/target specs.
     */
    static final int WHOLE_DOC_CHARS = 600;

    /** Hard ceiling per chunk; longer sections are split sequentially. */
    static final int MAX_CHUNK_CHARS = 800;

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}[ \\t]+(.+?)\\s*$");
    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9]{2,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");

    /** One retrievable passage: the section title (nullable) and its body text. */
    record Chunk(String title, String text) {}

    private KnowledgeEvidence() {}

    /**
     * Splits a knowledge document into retrieval chunks. A short doc is one chunk; a markdown doc
     * is split at heading lines (each chunk keeps its heading text); a heading-less doc is split
     * on blank lines. Oversized chunks are hard-split at {@value #MAX_CHUNK_CHARS} chars.
     */
    static List<Chunk> chunks(String content) {
        String normalized = normalize(content);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= WHOLE_DOC_CHARS) {
            return List.of(new Chunk(leadingTitle(normalized), normalized));
        }

        List<Chunk> out = new ArrayList<>();
        List<int[]> headings = new ArrayList<>();
        Matcher m = HEADING.matcher(normalized);
        while (m.find()) {
            headings.add(new int[] {m.start(), m.end()});
        }
        if (headings.isEmpty()) {
            for (String para : normalized.split("\n[ \\t]*\n[ \\t]*\n*")) {
                addSplit(out, null, para.strip());
            }
            return out;
        }

        // Preamble before the first heading (if any) becomes a title-less chunk.
        if (headings.get(0)[0] > 0) {
            addSplit(out, null, normalized.substring(0, headings.get(0)[0]).strip());
        }
        for (int i = 0; i < headings.size(); i++) {
            int from = headings.get(i)[0];
            int to = i + 1 < headings.size() ? headings.get(i + 1)[0] : normalized.length();
            Matcher hm = HEADING.matcher(normalized.substring(from, headings.get(i)[1]));
            String title = hm.find() ? hm.group(1).strip() : null;
            String body = normalized.substring(from, to).strip();
            addSplit(out, title, body);
        }
        return out;
    }

    /**
     * True when the document is small enough to be injected whole: {@link #chunks(String)} yields
     * a single passage and callers skip the keyword gate — for a compact doc the risk of losing
     * the answer to vocabulary mismatch outweighs the noise of returning everything.
     */
    static boolean isShortDoc(String content) {
        String normalized = normalize(content);
        return !normalized.isEmpty() && normalized.length() <= WHOLE_DOC_CHARS;
    }

    /** Extracts match terms from a query: ASCII/digit tokens (2+) and CJK bigrams, lower-cased. */
    static Set<String> terms(String query) {
        Set<String> out = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        String lowered = query.toLowerCase();
        Matcher ascii = ASCII_TOKEN.matcher(lowered);
        while (ascii.find()) {
            out.add(ascii.group());
        }
        Matcher cjk = CJK_RUN.matcher(query);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                out.add(run);
            } else {
                for (int i = 0; i + 2 <= run.length(); i++) {
                    out.add(run.substring(i, i + 2));
                }
            }
        }
        return out;
    }

    /** Relevance of one chunk: +2 per term in the body, +3 extra per term in the title. */
    static int score(Chunk chunk, Set<String> terms) {
        if (chunk == null || terms.isEmpty()) {
            return 0;
        }
        String body = chunk.text().toLowerCase();
        String title = chunk.title() == null ? "" : chunk.title().toLowerCase();
        int score = 0;
        for (String term : terms) {
            if (body.contains(term)) {
                score += 2;
            }
            if (!title.isEmpty() && title.contains(term)) {
                score += 3;
            }
        }
        return score;
    }

    private static void addSplit(List<Chunk> out, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() <= MAX_CHUNK_CHARS) {
            out.add(new Chunk(title, text));
            return;
        }
        for (int from = 0; from < text.length(); from += MAX_CHUNK_CHARS) {
            int to = Math.min(from + MAX_CHUNK_CHARS, text.length());
            out.add(new Chunk(title, text.substring(from, to)));
        }
    }

    private static String normalize(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").strip();
    }

    private static String leadingTitle(String doc) {
        Matcher m = HEADING.matcher(doc);
        return m.find() ? m.group(1).strip() : null;
    }
}
