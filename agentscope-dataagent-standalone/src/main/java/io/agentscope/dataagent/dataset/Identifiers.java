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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Identifier sanitising shared by the parsers (column names) and the provisioner (schema / table
 * names). Everything that reaches a DDL statement or is handed to the agent as a table name must
 * satisfy {@code [A-Za-z0-9_]+} so it also passes {@code JdbcSqlConnector}'s table-name guard.
 */
public final class Identifiers {

    private Identifiers() {}

    /**
     * Reduces {@code raw} to a safe lower-case SQL identifier. Characters outside {@code
     * [A-Za-z0-9_]} are dropped (so a Chinese header collapses to empty and the caller's {@code
     * fallback} is used). Guarantees a non-digit first character and a bounded length.
     */
    public static String sanitize(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String cleaned = sb.toString().replaceAll("_+", "_");
        cleaned = strip(cleaned, '_');
        if (cleaned.isEmpty()) {
            cleaned = fallback == null ? "" : fallback.toLowerCase();
        }
        if (!cleaned.isEmpty() && Character.isDigit(cleaned.charAt(0))) {
            cleaned = "_" + cleaned;
        }
        if (cleaned.length() > 60) {
            cleaned = strip(cleaned.substring(0, 60), '_');
        }
        return cleaned.isEmpty() ? "col" : cleaned;
    }

    /** Appends {@code _2}, {@code _3}, ... to duplicates so a list of identifiers is unique. */
    public static List<String> dedupe(List<String> names) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>(names.size());
        for (String n : names) {
            String candidate = n;
            int suffix = 2;
            while (!seen.add(candidate)) {
                candidate = n + "_" + suffix++;
            }
            out.add(candidate);
        }
        return out;
    }

    /** Stable 8-hex-char digest of {@code value}, for compact owner/dataset markers in names. */
    public static String shortHash(String value) {
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String strip(String s, char c) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == c) start++;
        while (end > start && s.charAt(end - 1) == c) end--;
        return s.substring(start, end);
    }
}
