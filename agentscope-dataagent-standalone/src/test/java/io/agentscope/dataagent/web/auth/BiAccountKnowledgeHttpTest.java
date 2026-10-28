package io.agentscope.dataagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.dataset.KnowledgeBaseMembershipService;
import io.agentscope.dataagent.dataset.KnowledgeBaseOperations;
import io.agentscope.dataagent.dataset.PublicKnowledgeBaseService;
import io.agentscope.dataagent.dataset.UserDirectoryImportService;
import io.agentscope.dataagent.web.api.KnowledgeBaseMembershipController;
import io.agentscope.dataagent.web.api.PublicKnowledgeBaseController;
import io.agentscope.dataagent.web.api.UserDirectoryImportController;
import io.agentscope.dataagent.web.api.UserProfileController;
import io.agentscope.dataagent.web.config.SecurityConfig;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.JpaUserStore;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = BiAccountKnowledgeHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:bi_account_knowledge;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never",
            "dataagent.auth.bi.account-login-enabled=true",
            "dataagent.auth.bi.enabled=false",
            "dataagent.auth.jwt-secret=account-knowledge-test-secret-at-least-32-bytes"
        })
@ActiveProfiles("h2")
class BiAccountKnowledgeHttpTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = UserEntity.class)
    @EnableJpaRepositories(basePackageClasses = UserEntityRepository.class)
    @Import({
        AuthSettings.class,
        AuthController.class,
        JwtService.class,
        BiTicketCodec.class,
        BiLoginService.class,
        SecurityConfig.class,
        KnowledgeBaseAccessService.class,
        KnowledgeBaseMembershipService.class,
        PublicKnowledgeBaseService.class,
        UserDirectoryImportService.class,
        io.agentscope.dataagent.dataset.KnowledgeBaseBrowseService.class,
        io.agentscope.dataagent.web.api.KnowledgeBaseBrowseController.class,
        UserProfileController.class,
        KnowledgeBaseMembershipController.class,
        PublicKnowledgeBaseController.class,
        UserDirectoryImportController.class
    })
    static class App {
        @Bean
        UserStore users(UserEntityRepository repository) {
            return new JpaUserStore(repository, false);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        io.agentscope.dataagent.dataset.DatasetService datasets() {
            return mock(io.agentscope.dataagent.dataset.DatasetService.class);
        }

        @Bean
        io.agentscope.dataagent.dataset.WrenProperties wren() {
            return mock(io.agentscope.dataagent.dataset.WrenProperties.class);
        }

        @Bean
        KnowledgeBaseOperations operations() {
            return mock(KnowledgeBaseOperations.class);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired UserStore users;
    @Autowired UserEntityRepository repository;
    @Autowired DatasetGroupRepository groups;
    @Autowired KnowledgeBaseAccessService access;
    @Autowired PublicKnowledgeBaseService publicKnowledge;
    @Autowired KnowledgeBaseMembershipService membership;
    @Autowired KnowledgeBaseOperations operations;
    @Autowired io.agentscope.dataagent.dataset.WrenProperties wren;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path files;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> request(String method, String path, String body, String token)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String login(String name) throws Exception {
        var response =
                request(
                        "POST",
                        "/api/auth/bi-account-login",
                        "{\"username\":\"" + name + "\"}",
                        null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("token").asText();
    }

    @Test
    void accountEntryCreatesOrdinaryUserAndNeverAcceptsClientRoles() throws Exception {
        String name = "first-" + UUID.randomUUID();
        var response =
                request(
                        "POST",
                        "/api/auth/bi-account-login",
                        "{\"username\":\"" + name + "\",\"roles\":[\"admin\"]}",
                        null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(users.findByUsername(name).orElseThrow().roles()).containsExactly("user");
        assertThat(
                        request(
                                        "POST",
                                        "/api/auth/bi-account-login",
                                        "{\"username\":\"bad account\"}",
                                        null)
                                .statusCode())
                .isEqualTo(400);
        assertThat(request("POST", "/api/auth/bi-login", "{\"ticket\":\"bad\"}", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void importsAreIdempotentPreserveRolesAndSupportDuplicateNamesAndFilters() throws Exception {
        String admin = "admin-" + UUID.randomUUID(),
                one = "one-" + UUID.randomUUID(),
                two = "two-" + UUID.randomUUID();
        var owner =
                users.createUser(
                        UUID.randomUUID().toString(), admin, "unused", List.of("user", "admin"));
        String token = login(admin),
                payload =
                        "{\"users\":[{\"username\":\""
                                + one
                                + "\",\"displayName\":\"张同名\",\"company\":\"公司甲\",\"department\":\"部门一\"},{\"username\":\""
                                + two
                                + "\",\"displayName\":\"张同名\",\"company\":\"公司乙\"}]}";
        assertThat(request("POST", "/api/admin/user-directory/import", payload, token).body())
                .contains("\"dryRun\":true");
        assertThat(users.findByUsername(one)).isEmpty();
        String apply = payload.substring(0, payload.length() - 1) + ",\"dryRun\":false}";
        assertThat(request("POST", "/api/admin/user-directory/import", apply, token).statusCode())
                .isEqualTo(200);
        String stable = users.findByUsername(one).orElseThrow().userId();
        assertThat(request("POST", "/api/admin/user-directory/import", apply, token).body())
                .contains("\"created\":0");
        assertThat(users.findByUsername(one).orElseThrow().userId()).isEqualTo(stable);
        var group =
                groups.save(
                        new DatasetGroupEntity(
                                UUID.randomUUID().toString(), owner.userId(), "目录测试", null));
        assertThat(
                        membership
                                .filteredDirectory(
                                        owner.userId(), group.getId(), "张同名", "", "", 0, 20)
                                .total())
                .isEqualTo(2);
        assertThat(
                        membership
                                .filteredDirectory(
                                        owner.userId(), group.getId(), "张同名", "公司甲", "部门一", 0, 20)
                                .items()
                                .get(0)
                                .userId())
                .isEqualTo(stable);
        membership.batchGrant(
                owner.userId(),
                group.getId(),
                List.of(stable, users.findByUsername(two).orElseThrow().userId()),
                "QUERY");
        assertThat(access.requireQuery(stable, group.getId())).isNotNull();
        assertThat(
                        request("POST", "/api/admin/user-directory/import", apply, login(one))
                                .statusCode())
                .isEqualTo(403);
        String duplicate =
                "{\"users\":[{\"username\":\"a\"},{\"username\":\"A\"}],\"dryRun\":false}";
        assertThat(
                        request("POST", "/api/admin/user-directory/import", duplicate, token)
                                .statusCode())
                .isEqualTo(400);
        assertThat(users.findByUsername("a")).isEmpty();
    }

    @Test
    void readableHttpProjectionUsesPublishedJsonAndRejectsUnassignedUsers() throws Exception {
        String suffix = UUID.randomUUID().toString();
        var owner =
                users.createUser("owner-" + suffix, "owner-" + suffix, "unused", List.of("admin"));
        String readerName = "browse-" + suffix, token = login(readerName);
        String reader = users.findByUsername(readerName).orElseThrow().userId();
        var group =
                new DatasetGroupEntity(UUID.randomUUID().toString(), owner.userId(), "浏览验收", null);
        group.setMdlVersion(1);
        groups.save(group);
        org.mockito.Mockito.when(wren.groupRoot(group.getId())).thenReturn(files);
        java.nio.file.Files.createDirectories(files.resolve("published/target"));
        java.nio.file.Files.writeString(
                files.resolve("published/target/mdl.json"),
                "{\"models\":[{\"name\":\"sales\"}],\"relationships\":[]}");
        String path = "/api/knowledge-bases/" + group.getId() + "/browse";
        assertThat(request("GET", path, null, token).statusCode()).isEqualTo(404);
        membership.grant(owner.userId(), group.getId(), reader, "QUERY");
        var response = request("GET", path, null, token);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.path("models").isArray()).isTrue();
        assertThat(data.path("models").get(0).path("name").asText()).isEqualTo("sales");
        assertThat(
                        request(
                                        "PUT",
                                        "/api/dataset-groups/"
                                                + group.getId()
                                                + "/members/"
                                                + owner.userId(),
                                        "{\"role\":\"MANAGE\"}",
                                        token)
                                .statusCode())
                .isEqualTo(403);
        membership.revoke(owner.userId(), group.getId(), reader);
        assertThat(request("GET", path, null, token).statusCode()).isEqualTo(404);
    }

    @Test
    void directoryPreviewAcceptsPayloadBeyondDefaultCodecBuffer() throws Exception {
        String suffix = UUID.randomUUID().toString(), name = "bulk-admin-" + suffix;
        users.createUser(UUID.randomUUID().toString(), name, "unused", List.of("admin"));
        var rows = new java.util.ArrayList<UserDirectoryImportService.Row>();
        for (int i = 0; i < 1500; i++)
            rows.add(
                    new UserDirectoryImportService.Row(
                            "bulk-" + suffix + "-" + i, "测试用户", "公司", "部门".repeat(80)));
        String payload = json.writeValueAsString(java.util.Map.of("users", rows, "dryRun", true));
        assertThat(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(262144);
        var response = request("POST", "/api/admin/user-directory/import", payload, login(name));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("total").asInt()).isEqualTo(1500);
        assertThat(users.findByUsername(rows.get(0).username())).isEmpty();
    }

    @Test
    void failedBatchDoesNotLeavePartialGrants() {
        String suffix = UUID.randomUUID().toString();
        var owner =
                users.createUser("owner-" + suffix, "owner-" + suffix, "unused", List.of("admin"));
        var target = users.createUser("a-" + suffix, "a-" + suffix, "unused", List.of("user"));
        var group =
                groups.save(
                        new DatasetGroupEntity(
                                UUID.randomUUID().toString(), owner.userId(), "事务测试", null));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                membership.batchGrant(
                                        owner.userId(),
                                        group.getId(),
                                        List.of(target.userId(), "zz-missing"),
                                        "QUERY"))
                .isInstanceOf(io.agentscope.dataagent.dataset.DatasetException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> access.requireQuery(target.userId(), group.getId()))
                .isInstanceOf(io.agentscope.dataagent.dataset.DatasetException.class);
    }

    @Test
    void publicAccessIsDynamicReadOnlyAndRevokedOnNextCheck() throws Exception {
        String name = "public-admin-" + UUID.randomUUID();
        var admin =
                users.createUser(UUID.randomUUID().toString(), name, "unused", List.of("admin"));
        String token = login(name);
        var group =
                new DatasetGroupEntity(UUID.randomUUID().toString(), admin.userId(), "公开样例", null);
        group.setMdlVersion(1);
        groups.save(group);
        String path = "/api/admin/public-knowledge-bases/" + group.getId();
        assertThat(request("PUT", path, "{\"publicQuery\":true}", token).statusCode())
                .isEqualTo(200);
        String reader = "future-" + UUID.randomUUID(), readerToken = login(reader);
        String id = users.findByUsername(reader).orElseThrow().userId();
        assertThat(access.requireQuery(id, group.getId())).isNotNull();
        assertThat(access.page(id, 0, 20, "公开样例", "name", false).getContent())
                .extracting(DatasetGroupEntity::getId)
                .contains(group.getId());
        assertThat(access.page(id, 0, 20, "公开样例", "name", true).getContent()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> access.requireManage(id, group.getId()))
                .isInstanceOf(io.agentscope.dataagent.dataset.DatasetException.class);
        assertThat(request("PUT", path, "{\"publicQuery\":false}", readerToken).statusCode())
                .isEqualTo(403);
        assertThat(request("PUT", path, "{\"publicQuery\":false}", token).statusCode())
                .isEqualTo(200);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> access.requireQuery(id, group.getId()))
                .isInstanceOf(io.agentscope.dataagent.dataset.DatasetException.class);
        assertThat(request("GET", "/api/admin/public-knowledge-bases", null, null).statusCode())
                .isEqualTo(401);
    }
}
