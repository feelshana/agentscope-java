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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the generated {@code profiles.yml} contract against wren's profile loader (specs/010
 * M3/M4): {@code port} must stay a plain digit scalar (wren's {@code StrPort} validator accepts
 * the string, {@code connector/mysql.py} casts it with {@code int(info.port)} — a doubly quoted
 * {@code ''3306''} would reach the connector as {@code "'3306'"} and crash), {@code $$} keeps
 * literal dollar signs alive through {@code expand_profile_secrets}, {@code ssl_mode} never
 * emits a value wren would misinterpret as "require SSL", and every renderable MySQL external
 * datasource gets its {@code ext-<id>} entry rebuilt from the database on each ensure.
 */
class WrenProfileHomeTest {

    @TempDir Path tmp;

    private DatasetStoreProperties storeProps;
    private ExternalDataSourceRepository externalSources;
    private WrenProperties props;

    @BeforeEach
    void setUp() {
        storeProps = mock(DatasetStoreProperties.class);
        externalSources = mock(ExternalDataSourceRepository.class);
        when(externalSources.findAll()).thenReturn(List.of());
        props = new WrenProperties("wren", tmp.toString(), "dataagent", "mysql", 10);
    }

    @Test
    void parseJdbcUrlExtractsHostPortDatabase() {
        Map<String, String> parts =
                WrenProfileHome.parseJdbcUrl(
                        "jdbc:mysql://db.example.com:3307/data_agent?useUnicode=true"
                                + "&characterEncoding=utf8&useSSL=false");

        assertThat(parts)
                .containsEntry("host", "db.example.com")
                .containsEntry("port", "3307")
                .containsEntry("database", "data_agent");
    }

    @Test
    void parseJdbcUrlDefaultsPortAndAcceptsBareHost() {
        Map<String, String> parts =
                WrenProfileHome.parseJdbcUrl("jdbc:mysql://localhost/data_agent");

        assertThat(parts)
                .containsEntry("host", "localhost")
                .containsEntry("port", "3306")
                .containsEntry("database", "data_agent");
    }

    /**
     * External-source urls are runtime-configured and may carry no database segment (the
     * Tencent-Cloud case from ADR 0021); the lenient parser keeps them usable because the MDL's
     * rewritten physical SQL is schema-qualified anyway.
     */
    @Test
    void parseJdbcUrlAllowEmptyDbKeepsDatabaseBlank() {
        Map<String, String> parts =
                WrenProfileHome.parseJdbcUrlAllowEmptyDb(
                        "jdbc:mysql://bj-cdb-x.sql.tencentcdb.com:27148?useSSL=true");

        assertThat(parts)
                .containsEntry("host", "bj-cdb-x.sql.tencentcdb.com")
                .containsEntry("port", "27148")
                .containsEntry("database", "");
    }

    @Test
    void parseJdbcUrlRejectsMalformedUrls() {
        assertThatThrownBy(() -> WrenProfileHome.parseJdbcUrl("jdbc:mysql:data_agent"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("'//'");
        assertThatThrownBy(() -> WrenProfileHome.parseJdbcUrl("jdbc:mysql://localhost/"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("缺少数据库名");
        assertThatThrownBy(() -> WrenProfileHome.parseJdbcUrl("jdbc:mysql://localhost"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("缺少数据库名");
    }

    @Test
    void renderPinsEveryProfileField() {
        stubStore("jdbc:mysql://127.0.0.1:3306/data_agent?useSSL=false", "root", "pa$sword");

        assertThat(home().render())
                .isEqualTo(
                        "active: dataagent\n"
                                + "profiles:\n"
                                + "  dataagent:\n"
                                + "    datasource: mysql\n"
                                + "    host: 127.0.0.1\n"
                                + "    port: '3306'\n"
                                + "    database: data_agent\n"
                                + "    user: root\n"
                                + "    password: pa$$sword\n"
                                + "    ssl_mode: DISABLED\n");
    }

    /**
     * wren's connector treats every non-{@code disabled} ssl_mode as "require SSL"; the url is
     * the only signal the platform has, and an unspecified url must not force SSL on the
     * internal dataset store.
     */
    @Test
    void sslModeFollowsExplicitUrlSwitchesOnly() {
        stubStore("jdbc:mysql://h:3306/db", "u", "p");
        assertThat(home().render()).contains("ssl_mode: DISABLED");

        stubStore("jdbc:mysql://h:3306/db?useSSL=false&serverTimezone=Asia/Shanghai", "u", "p");
        assertThat(home().render()).contains("ssl_mode: DISABLED");

        stubStore("jdbc:mysql://h:3306/db?useSSL=true", "u", "p");
        assertThat(home().render()).contains("ssl_mode: ENABLED");

        stubStore("jdbc:mysql://h:3306/db?sslMode=REQUIRED", "u", "p");
        assertThat(home().render()).contains("ssl_mode: ENABLED");
    }

    @Test
    void renderFailsWithoutDatasetUrl() {
        stubStore("", "root", "p");

        assertThatThrownBy(() -> home().render())
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("dataagent.dataset.datasource.url");
    }

    /** specs/010 M4: one ext-<id> entry per MySQL external source, non-MySQL kinds skipped. */
    @Test
    void renderAllAppendsExternalMysqlProfilesAndSkipsOtherKinds() {
        stubStore("jdbc:mysql://127.0.0.1:3306/data_agent?useSSL=false", "root", "p");
        ExternalDataSourceEntity tencent =
                external(
                        "ds-1",
                        "mysql",
                        "jdbc:mysql://bj-cdb-x.sql.tencentcdb.com:27148?useSSL=true",
                        "cloud",
                        "pw");
        ExternalDataSourceEntity local =
                external("ds-2", "mysql", "jdbc:mysql://10.0.0.8:3307/report", "ops", "pw2");
        ExternalDataSourceEntity pg =
                external("ds-3", "postgresql", "jdbc:postgresql://10.0.0.9/analytics", "bi", "");

        String yaml = home().renderAll(List.of(tencent, local, pg));

        assertThat(yaml)
                .contains("  ext-ds-1:")
                .contains("    host: bj-cdb-x.sql.tencentcdb.com")
                .contains("    port: '27148'")
                .contains("    database: ''")
                .contains("    user: cloud")
                .contains("    ssl_mode: ENABLED")
                .contains("  ext-ds-2:")
                .contains("    database: report")
                .doesNotContain("ext-ds-3")
                .doesNotContain("postgresql");
        // The default entry stays first and the active profile is untouched.
        assertThat(yaml.startsWith("active: dataagent\nprofiles:\n  dataagent:\n")).isTrue();
    }

    /** The file follows the database (single source of truth): edits replace the previous set. */
    @Test
    void ensureAllRebuildsFileFromProvidedSources() throws Exception {
        stubStore("jdbc:mysql://127.0.0.1:3306/data_agent?useSSL=false", "root", "p");
        WrenProfileHome home = home();

        home.ensureAll(List.of(external("ds-1", "mysql", "jdbc:mysql://h:3307/db1", "u", "p")));
        Path file = tmp.resolve(".wren").resolve("profiles.yml");
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .isEqualTo(
                        home.renderAll(
                                List.of(
                                        external(
                                                "ds-1",
                                                "mysql",
                                                "jdbc:mysql://h:3307/db1",
                                                "u",
                                                "p"))));

        // A later ensure with the source removed (deleted datasource) drops its entry again.
        home.ensureAll(List.of());
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .isEqualTo(home.render())
                .doesNotContain("ext-ds-1");
    }

    @Test
    void ensureWritesProfileOnceAndRepairsDrift() throws Exception {
        stubStore("jdbc:mysql://127.0.0.1:3306/data_agent?useSSL=false", "root", "p");
        WrenProfileHome home = home();

        Path file = home.ensure().resolve("profiles.yml");
        assertThat(file).exists();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(home.render());

        // Second ensure is a no-op: identical content is not rewritten.
        Path again = home.ensure();
        assertThat(again).isEqualTo(tmp.resolve(".wren"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(home.render());

        // Drifted content (partial write, manual edit) is repaired on the next ensure.
        Files.writeString(file, "active: broken\n", StandardCharsets.UTF_8);
        home.ensure();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(home.render());
    }

    private WrenProfileHome home() {
        return new WrenProfileHome(
                props,
                storeProps,
                externalSources,
                new ExternalDataSourcePolicy(
                        "10.0.0.8:3307,127.0.0.1:3306,bj-cdb-x.sql.tencentcdb.com:27148,db.example.com:3307,h:3306,h:3307,localhost:3306"));
    }

    private static ExternalDataSourceEntity external(
            String id, String kind, String url, String user, String password) {
        return new ExternalDataSourceEntity(id, "owner", id, kind, url, user, password, true);
    }

    private void stubStore(String url, String user, String password) {
        when(storeProps.url()).thenReturn(url);
        when(storeProps.username()).thenReturn(user);
        when(storeProps.password()).thenReturn(password);
    }
}
