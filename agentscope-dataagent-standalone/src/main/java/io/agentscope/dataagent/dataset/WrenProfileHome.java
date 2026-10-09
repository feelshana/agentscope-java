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

import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Platform-managed {@code WREN_HOME} holding the generated {@code profiles.yml} (specs/010 M3).
 *
 * <p>The runtime {@code wren serve mcp} subprocess resolves its connection profile from {@code
 * $WREN_HOME/profiles.yml} at startup, so the platform must own that file instead of relying on
 * the operator's {@code ~/.wren} (which may not exist on a server, or may pin another database).
 * The default {@code dataagent} profile binds the uploaded-{@code ds_*}-table channel to the same
 * MySQL as {@code dataagent.dataset.datasource.url}; since specs/010 M4 one additional {@code
 * ext-<datasourceId>} entry is rendered per configured MySQL external data source, because
 * external sources are registered at runtime (from the UI) and their tables live on other
 * instances — a published group picks its entry via {@code published/wren-source.properties}.
 *
 * <p>The file is always rebuilt in full from the database (idempotent, single source of truth),
 * so a restart never strips the external entries previously published groups rely on.
 *
 * <p>{@code wren.profile.expand_profile_secrets} resolves {@code ${VAR}} references from the
 * environment at connection time, so every literal {@code $} in rendered values is escaped as
 * {@code $$} (the module's own Template escape) to keep passwords with dollar signs intact.
 */
@Component
public class WrenProfileHome {

    private static final Logger log = LoggerFactory.getLogger(WrenProfileHome.class);

    /** Prefix of the per-datasource profile entries ({@code ext-<datasourceId>}, specs/010 M4). */
    static final String EXTERNAL_PROFILE_PREFIX = "ext-";

    private final WrenProperties wrenProps;
    private final DatasetStoreProperties storeProps;
    private final ExternalDataSourceRepository externalSources;
    private final ExternalDataSourcePolicy connectionPolicy;

    public WrenProfileHome(
            WrenProperties wrenProps,
            DatasetStoreProperties storeProps,
            ExternalDataSourceRepository externalSources,
            ExternalDataSourcePolicy connectionPolicy) {
        this.wrenProps = wrenProps;
        this.storeProps = storeProps;
        this.externalSources = externalSources;
        this.connectionPolicy = connectionPolicy;
    }

    /**
     * Ensures {@code <mdlRoot>/.wren/profiles.yml} reflects the current dataset-store settings
     * and every configured external data source; returns the home directory to hand to the
     * subprocess as {@code WREN_HOME}. The file is only rewritten when its content changes, so
     * an already-running instance is never surprised by a partial write.
     */
    public Path ensure() {
        return ensureAll(externalSources.findAll());
    }

    /**
     * Same as {@link #ensure()} but with the external-source list supplied by the caller — the
     * publish path re-renders right after datasource edits so the file matches the database the
     * group is about to be bound to.
     */
    public Path ensureAll(List<ExternalDataSourceEntity> externalSources) {
        Path home = wrenProps.wrenHome();
        try {
            Files.createDirectories(home);
            Path file = home.resolve("profiles.yml");
            String content = renderAll(externalSources);
            if (!Files.exists(file)
                    || !content.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                Files.writeString(file, content, StandardCharsets.UTF_8);
                log.info(
                        "Wren connection profiles written: {} ({} profile(s))",
                        file,
                        1 + externalSources.stream().filter(this::isRenderableExternal).count());
            }
            return home;
        } catch (IOException e) {
            throw new DatasetException("写入 wren 连接档案失败：" + e.getMessage(), e);
        }
    }

    /** Renders {@code profiles.yml} with only the default dataset-store profile. */
    String render() {
        return renderAll(List.of());
    }

    /**
     * Renders {@code profiles.yml} from the dataset-store JDBC url plus one {@code ext-…} entry
     * per renderable MySQL external source. Non-MySQL sources are skipped: their MDL dialect
     * (data_source / parse-types) is not isolated yet (ADR 0021 defers PostgreSQL).
     */
    String renderAll(List<ExternalDataSourceEntity> externalSources) {
        String url = storeProps.url();
        if (url == null || url.isBlank()) {
            throw new DatasetException(
                    "未配置 dataagent.dataset.datasource.url，无法生成 wren 连接档案（wren 查询依赖数据集库连接）");
        }
        Map<String, String> def = parseJdbcUrl(url);
        StringBuilder sb = new StringBuilder();
        sb.append("active: ").append(escape(wrenProps.profile())).append('\n');
        sb.append("profiles:\n");
        sb.append(
                renderProfileEntry(
                        wrenProps.profile(),
                        wrenProps.dataSource(),
                        def.get("host"),
                        def.get("port"),
                        def.get("database"),
                        storeProps.username(),
                        storeProps.password(),
                        sslMode(url)));
        for (ExternalDataSourceEntity ds : externalSources) {
            if (!isRenderableExternal(ds)) {
                log.warn(
                        "WrenProfileHome: skipping non-MySQL external datasource '{}' (kind={});"
                                + " wren publish for it is not supported yet",
                        ds.getId(),
                        ds.getKind());
                continue;
            }
            String validated;
            try {
                validated = connectionPolicy.normalize(ds.getKind(), ds.getJdbcUrl());
            } catch (DatasetException ex) {
                log.warn(
                        "Skipping disallowed external profile {}: {}", ds.getId(), ex.getMessage());
                continue;
            }
            Map<String, String> parts = parseJdbcUrlAllowEmptyDb(validated);
            sb.append(
                    renderProfileEntry(
                            externalProfileName(ds.getId()),
                            "mysql",
                            parts.get("host"),
                            parts.get("port"),
                            parts.get("database"),
                            ds.getUsername(),
                            ds.getPassword(),
                            sslMode(ds.getJdbcUrl())));
        }
        return sb.toString();
    }

    private boolean isRenderableExternal(ExternalDataSourceEntity ds) {
        return "mysql".equalsIgnoreCase(ds.getKind()) && ds.getJdbcUrl() != null;
    }

    /** Profile name for an external datasource entry: {@code ext-<datasourceId>}. */
    static String externalProfileName(String datasourceId) {
        return EXTERNAL_PROFILE_PREFIX + datasourceId;
    }

    /**
     * Splits {@code jdbc:mysql://host[:port]/database?params} into {@code host} / {@code port} /
     * {@code database}. The port defaults to 3306; a url without a database segment is an error
     * because the default profile must pin the exact database the {@code ds_*} tables live in.
     */
    static Map<String, String> parseJdbcUrl(String url) {
        Map<String, String> parts = parseJdbcUrlAllowEmptyDb(url);
        if (parts.get("database").isBlank()) {
            throw new DatasetException("无法解析数据集库 JDBC url（缺少数据库名）：" + url);
        }
        return parts;
    }

    /**
     * Lenient variant for external datasource urls, which are runtime-configured and may carry no
     * database segment ({@code jdbc:mysql://host:port}); the database is then rendered as an
     * empty string — wren's pydantic {@code MySqlConnectionInfo} accepts it, MySQL allows
     * connecting without a default schema, and every rewritten physical SQL is schema-qualified
     * by the MDL {@code table_reference} anyway.
     */
    static Map<String, String> parseJdbcUrlAllowEmptyDb(String url) {
        String head = url;
        int qi = head.indexOf('?');
        if (qi >= 0) {
            head = head.substring(0, qi);
        }
        int marker = head.indexOf("//");
        if (marker < 0) {
            throw new DatasetException("无法解析 JDBC url（缺少 '//'）：" + url);
        }
        String rest = head.substring(marker + 2);
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String database = slash >= 0 ? rest.substring(slash + 1) : "";
        String host = authority;
        String port = "3306";
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            String rawPort = authority.substring(colon + 1);
            if (!rawPort.isBlank()) {
                port = rawPort;
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("host", host.isBlank() ? "127.0.0.1" : host);
        out.put("port", port);
        out.put("database", database);
        return out;
    }

    /**
     * Maps the JDBC url's SSL switches onto wren's three-state {@code ssl_mode}. wren treats
     * every non-{@code disabled} value as "require SSL" ({@code connector/mysql.py}
     * {@code _mysql_ssl_kwargs}), so JDBC's {@code PREFERRED} fallback cannot be expressed:
     * anything that is not an explicit "require SSL" switch maps to {@code DISABLED} — the
     * dataset store is the platform-managed internal database and the shipped default url pins
     * {@code useSSL=false}. {@code VERIFY_CA} is not expressible either (the profile carries no
     * CA path), so explicit SSL switches map to {@code ENABLED} (encrypted, no CA check).
     */
    private static String sslMode(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("sslmode=required")
                || lower.contains("sslmode=verify_ca")
                || lower.contains("sslmode=verify_identity")
                || lower.contains("usessl=true")) {
            return "ENABLED";
        }
        return "DISABLED";
    }

    /**
     * Deterministic single-profile {@code profiles.yml} (test anchor; the live file is rendered
     * by {@link #renderAll}). Values go through the shared MDL YAML scalar rules and an extra
     * {@code $} escape (see the class javadoc).
     */
    static String renderProfilesYaml(
            String profile,
            String dataSource,
            String host,
            String port,
            String database,
            String user,
            String password,
            String sslMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("active: ").append(escape(profile)).append('\n');
        sb.append("profiles:\n");
        sb.append(
                renderProfileEntry(
                        profile, dataSource, host, port, database, user, password, sslMode));
        return sb.toString();
    }

    /** One indented {@code profiles.yml} entry; {@code database} may be empty (external url). */
    private static String renderProfileEntry(
            String profile,
            String dataSource,
            String host,
            String port,
            String database,
            String user,
            String password,
            String sslMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(escape(profile)).append(":\n");
        sb.append("    datasource: ").append(escape(dataSource)).append('\n');
        sb.append("    host: ").append(escape(host)).append('\n');
        // No extra quoting here: yamlScalar already quotes digit-only values ('3306'), which
        // wren's StrPort validator accepts and the connector casts with int(info.port).
        sb.append("    port: ").append(escape(port)).append('\n');
        sb.append("    database: ").append(escape(database)).append('\n');
        sb.append("    user: ").append(escape(user)).append('\n');
        sb.append("    password: ").append(escape(password)).append('\n');
        sb.append("    ssl_mode: ").append(escape(sslMode)).append('\n');
        return sb.toString();
    }

    /** YAML-safe scalar with the profile module's {@code ${VAR}} expansion escaped. */
    private static String escape(String value) {
        String v = value == null ? "" : value.replace("$", "$$");
        return MdlPublishService.yamlScalar(v);
    }
}
