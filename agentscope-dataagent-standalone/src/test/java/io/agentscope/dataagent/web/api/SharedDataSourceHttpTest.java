package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DataSourceIntrospector;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DemoDataService;
import io.agentscope.dataagent.dataset.ExternalDataSourceAccess;
import io.agentscope.dataagent.dataset.ExternalDataSourcePolicy;
import io.agentscope.dataagent.dataset.ExternalDataSourceService;
import io.agentscope.dataagent.web.auth.AuthSettings;
import io.agentscope.dataagent.web.auth.JwtService;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.config.SecurityConfig;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceGrantRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.JpaUserStore;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real HTTP, repositories, transactions and source lock; only external DB is stubbed. */
@SpringBootTest(
        classes = SharedDataSourceHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:source135;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=10000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never",
            "dataagent.jwt.secret=test-data-source-secret-at-least-32-characters-long"
        })
class SharedDataSourceHttpTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = UserEntity.class)
    @EnableJpaRepositories(basePackageClasses = UserEntityRepository.class)
    @Import({
        SecurityConfig.class,
        AuthSettings.class,
        JwtService.class,
        ExternalDataSourceController.class,
        ExternalDataSourceAccess.class,
        ExternalDataSourceService.class,
        ExternalDataSourcePolicy.class
    })
    static class App {
        @Bean
        UserStore users(UserEntityRepository repository) {
            return new JpaUserStore(repository, false);
        }

        @Bean
        DemoDataService demo() {
            return mock(DemoDataService.class);
        }

        @Bean
        DataSourceIntrospector introspector() {
            var reader = mock(DataSourceIntrospector.class);
            when(reader.listSchemas(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(List.of("sales"));
            return reader;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired UserEntityRepository users;
    @Autowired ExternalDataSourceRepository sources;
    @Autowired ExternalDataSourceGrantRepository grants;
    @Autowired DatasetRepository datasets;
    @Autowired ExternalDataSourceAccess access;
    @Autowired ExternalDataSourceService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        datasets.deleteAll();
        grants.deleteAll();
        sources.deleteAll();
        users.deleteAll();
        jdbc.update("delete from dataagent_datasource_audit");
        user("owner", "创建人", "user");
        user("use", "使用者", "user");
        user("manager", "维护者", "user");
        user("outsider", "其他人", "user");
        user("admin", "管理员", "user,admin");
    }

    void user(String id, String name, String roles) {
        var u = new UserEntity(id, id, "unused", roles);
        u.setDisplayName(name);
        users.save(u);
    }

    String create(String actor) throws Exception {
        var response = call(actor, "POST", "", connection("销售库", "secret", "db.internal"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("secret", "password");
        return json.readTree(response.body()).path("id").asText();
    }

    Map<String, Object> connection(String name, String password, String host) {
        return Map.of(
                "name",
                name,
                "host",
                host,
                "port",
                3306,
                "database",
                "sales",
                "username",
                "reader",
                "password",
                password,
                "kind",
                "mysql");
    }

    HttpResponse<String> call(String actor, String method, String path, Object body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/datasources" + path))
                        .header(
                                "Authorization",
                                "Bearer " + jwt.generate(actor, actor, List.of("user")))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    void grant(String id, String user, String role) throws Exception {
        assertThat(
                        call("owner", "PUT", "/" + id + "/members/" + user, Map.of("role", role))
                                .statusCode())
                .isEqualTo(200);
    }

    @Test
    void globalAdminHasCreatorAuthorityWithoutAnExplicitGrant() throws Exception {
        String id = create("owner");
        JsonNode view = json.readTree(call("admin", "GET", "", null).body()).get(0);
        assertThat(view.path("canManage").asBoolean()).isTrue();
        assertThat(view.path("canGovern").asBoolean()).isTrue();
        assertThat(view.path("shared").asBoolean()).isFalse();
        assertThat(
                        call("admin", "PUT", "/" + id, connection("管理员改名", "", "db.internal"))
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        call("admin", "PUT", "/" + id + "/members/use", Map.of("role", "USE"))
                                .statusCode())
                .isEqualTo(200);
        assertThat(call("admin", "DELETE", "/" + id + "/members/use", null).statusCode())
                .isEqualTo(200);
        assertThat(
                        call("admin", "PUT", "/" + id + "/enabled", Map.of("enabled", false))
                                .statusCode())
                .isEqualTo(200);
        assertThat(call("admin", "DELETE", "/" + id, null).statusCode()).isEqualTo(200);
        assertThat(sources.existsById(id)).isFalse();
    }

    @Test
    void independentDuplicateEndpointsAndUseRoleNeverExposeConfiguration() throws Exception {
        String id = create("owner");
        String other = create("outsider");
        assertThat(other).isNotEqualTo(id);
        assertThat(json.readTree(call("use", "GET", "", null).body())).isEmpty();
        assertThat(call("use", "GET", "/" + id + "/schemas", null).statusCode()).isEqualTo(404);
        grant(id, "use", "USE");
        var list = call("use", "GET", "", null);
        assertThat(list.body()).doesNotContain("db.internal", "reader", "secret");
        assertThat(json.readTree(list.body()).get(0).path("shared").asBoolean()).isTrue();
        assertThat(call("use", "GET", "/" + id + "/schemas", null).statusCode()).isEqualTo(200);
        assertThat(call("use", "PUT", "/" + id, connection("越权", "", "db.internal")).statusCode())
                .isEqualTo(403);
        assertThat(
                        call(
                                        "use",
                                        "POST",
                                        "/test-connection?sourceId=" + id,
                                        connection("借密码", "", "elsewhere.internal"))
                                .statusCode())
                .isEqualTo(403);
        assertThat(call("use", "GET", "/" + id + "/members", null).statusCode()).isEqualTo(403);
        assertThat(call("use", "DELETE", "/" + id, null).statusCode()).isEqualTo(403);
        assertThat(call("use", "PUT", "/" + id + "/enabled", Map.of("enabled", false)).statusCode())
                .isEqualTo(403);
    }

    @Test
    void managerConfiguresButCannotGovernOrRemoveFixedOwnerAdminRights() throws Exception {
        String id = create("owner");
        grant(id, "manager", "MANAGE");
        assertThat(
                        call(
                                        "manager",
                                        "PUT",
                                        "/" + id,
                                        connection("维护者改名", "rotated", "db.internal"))
                                .statusCode())
                .isEqualTo(200);
        assertThat(sources.findById(id).orElseThrow().getPassword()).isEqualTo("rotated");
        assertThat(
                        call("manager", "PUT", "/" + id + "/members/use", Map.of("role", "USE"))
                                .statusCode())
                .isEqualTo(403);
        assertThat(call("manager", "DELETE", "/" + id, null).statusCode()).isEqualTo(403);
        for (String fixed : List.of("owner", "admin"))
            assertThat(call("owner", "DELETE", "/" + id + "/members/" + fixed, null).statusCode())
                    .isEqualTo(400);
        var directory =
                call(
                        "owner",
                        "GET",
                        "/"
                                + id
                                + "/member-users?search="
                                + java.net.URLEncoder.encode(
                                        "使用", java.nio.charset.StandardCharsets.UTF_8),
                        null);
        assertThat(directory.statusCode()).isEqualTo(200);
        assertThat(json.readTree(directory.body()).path("total").asInt()).isEqualTo(1);
        assertThat(call("owner", "GET", "/" + id + "/member-users?size=1000", null).statusCode())
                .isEqualTo(400);
    }

    void bind(String id) {
        var dataset = new DatasetEntity();
        dataset.setId("bound");
        dataset.setOwnerId("use");
        dataset.setGroupId("kb");
        dataset.setName("订单");
        dataset.setSchemaName("sales");
        dataset.setTableName("orders");
        dataset.setOrigin("datasource");
        dataset.setExternalDataSourceId(id);
        datasets.save(dataset);
    }

    @Test
    void revocationKeepsBindingButDisableStopsReadsAndBoundTargetCannotChange() throws Exception {
        String id = create("owner");
        grant(id, "use", "USE");
        bind(id);
        assertThat(call("owner", "DELETE", "/" + id + "/members/use", null).statusCode())
                .isEqualTo(200);
        assertThat(call("use", "GET", "/" + id + "/schemas", null).statusCode()).isEqualTo(404);
        access.requireBindingsActive("kb");
        assertThat(datasets.existsById("bound")).isTrue();
        assertThat(call("admin", "DELETE", "/" + id, null).statusCode()).isEqualTo(409);
        assertThat(
                        call("admin", "PUT", "/" + id, connection("不换目标", "", "other.internal"))
                                .statusCode())
                .isEqualTo(409);
        assertThat(
                        call(
                                        "admin",
                                        "POST",
                                        "/test-connection?sourceId=" + id,
                                        connection("借密码", "", "other.internal"))
                                .statusCode())
                .isEqualTo(409);
        assertThat(
                        call("owner", "PUT", "/" + id + "/enabled", Map.of("enabled", false))
                                .statusCode())
                .isEqualTo(200);
        assertThat(call("admin", "GET", "/" + id + "/schemas", null).statusCode()).isEqualTo(409);
        assertThatThrownBy(() -> access.requireBindingsActive("kb"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("停用");
        assertThat(
                        call("admin", "PUT", "/" + id + "/enabled", Map.of("enabled", true))
                                .statusCode())
                .isEqualTo(200);
        access.requireBindingsActive("kb");
    }

    String storedPassword(String id) {
        return jdbc.queryForObject(
                "select password from dataagent_external_datasource where datasource_id = ?",
                String.class,
                id);
    }

    @Test
    void existingPlaintextPasswordSurvivesConfigurationEditsWithoutExposure() throws Exception {
        String id = create("owner");
        assertThat(storedPassword(id)).isEqualTo("secret");
        jdbc.update(
                "update dataagent_external_datasource set password = ? where datasource_id = ?",
                "legacy-secret",
                id);
        assertThat(sources.findById(id).orElseThrow().getPassword()).isEqualTo("legacy-secret");
        assertThat(call("owner", "GET", "", null).body()).doesNotContain("secret", "password");
        var response = call("owner", "PUT", "/" + id, connection("只改名称", "", "db.internal"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("secret", "password");
        assertThat(storedPassword(id)).isEqualTo("legacy-secret");
        assertThat(jdbc.queryForList("select * from dataagent_datasource_audit").toString())
                .doesNotContain("secret", "db.internal", "reader");
    }

    @Test
    void associationQueuedBehindRevocationRechecksRealGrantAfterSourceLock() throws Exception {
        String id = create("owner");
        grant(id, "use", "USE");
        var tx = new TransactionTemplate(transactions);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        AtomicReference<Throwable> denied = new AtomicReference<>();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var revoke =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                access.lock(id);
                                                service.revoke("owner", id, "use");
                                                held.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "release timeout");
                                                } catch (InterruptedException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var association =
                    pool.submit(
                            () -> {
                                try {
                                    tx.executeWithoutResult(
                                            s -> {
                                                queued.countDown();
                                                access.requireUseLocked("use", access.lock(id));
                                            });
                                } catch (Throwable failure) {
                                    denied.set(failure);
                                }
                            });
            assertThat(queued.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            revoke.get(5, TimeUnit.SECONDS);
            association.get(5, TimeUnit.SECONDS);
            assertThat(denied.get())
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("未授权");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
