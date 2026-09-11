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
package io.agentscope.dataagent.dataset.parser;

import java.io.IOException;
import java.io.InputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * Extracts plain text from an uploaded {@code .docx} used as a dataset description. Only
 * paragraphs are read; the text is metadata for humans and the agent, never queried as data.
 */
public final class DocxDescriptionExtractor {

    private DocxDescriptionExtractor() {}

    public static String extract(InputStream input, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(input)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text.trim());
                }
            }
        }
        String out = sb.toString().trim();
        return out.length() <= maxChars ? out : out.substring(0, maxChars);
    }
}
