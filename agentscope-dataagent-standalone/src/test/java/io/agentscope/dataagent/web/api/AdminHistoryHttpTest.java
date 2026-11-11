package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.artifact.ArtifactStore;
import io.agentscope.dataagent.web.auth.AuthSettings;
import io.agentscope.dataagent.web.auth.JwtService;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.config.SecurityConfig;
import io.agentscope.dataagent.web.persistence.jpa.ArtifactEntity;
import io.agentscope.dataagent.web.persistence.jpa.ArtifactRepository;
import io.agentscope.dataagent.web.persistence.jpa.JpaUserStore;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = AdminHistoryHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:admin_history;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never",
            "dataagent.jwt.secret=local-test-secret-at-least-thirty-two-bytes"
        })
class AdminHistoryHttpTest {
    @TempDir static Path files;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dataagent.artifacts.directory", () -> files.toString());
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = UserEntity.class)
    @EnableJpaRepositories(basePackageClasses = UserEntityRepository.class)
    @Import({
        SecurityConfig.class,
        AuthSettings.class,
        JwtService.class,
        AdminConversationController.class,
        AdminHistoryOverviewController.class,
        io.agentscope.dataagent.web.session.AdminHistoryQueryService.class,
        GlobalAdministratorController.class,
        ArtifactController.class,
        ArtifactStore.class
    })
    static class App {
        @Bean
        UserStore userStore(UserEntityRepository repository) {
            return new JpaUserStore(repository, false);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired UserEntityRepository users;
    @Autowired SessionRegistryRepository registry;
    @Autowired SessionHistoryRepository history;
    @Autowired SessionMessageRepository messages;
    @Autowired ArtifactRepository artifacts;
    @Autowired JwtService jwt;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void clearRecords() {
        messages.deleteAll();
        history.deleteAll();
        registry.deleteAll();
        artifacts.deleteAll();
        users.deleteAll();
    }

    @Test
    void overviewUsesOneFilterForContentCountsUsersStablePagesAndExport() throws Exception {
        addUser("overview-admin", "管理员", "user,admin");
        addUser("user-a", "同名用户", "user");
        addUser("user-b", "同名用户", "user");
        long time = Instant.parse("2026-11-05T16:15:00Z").toEpochMilli();
        for (int i = 0; i < 24; i++) {
            String key = "ov-" + String.format("%02d", i);
            addSession(key, i < 12 ? "user-a" : "user-b", "main", time);
            for (int j = 0; j < (i == 0 ? 3 : 1); j++) {
                var m = message(j, "USER", j == 2 ? "正文关键字 100%" : "一般问题", time - 86400000);
                m.id = key + "-" + j;
                m.sessionKey = key;
                m.project("USER", j == 2 ? "正文关键字 100%" : "一般问题");
                messages.save(m);
            }
            var a = message(8, "ASSISTANT", "只在回复出现", time);
            a.id = key + "-a";
            a.sessionKey = key;
            a.project("ASSISTANT", "只在回复出现");
            messages.save(a);
        }
        var h = new SessionHistoryEntity();
        h.sessionKey = "ov-00";
        h.title = "=SUM(1,2) 标题";
        h.activityMs = time;
        history.save(h);
        addSession("ignored-sub", "user-a", "sub", time);
        String admin = jwt.generate("overview-admin", "overview-admin", List.of("user", "admin"));
        String ordinary = jwt.generate("user-a", "user-a", List.of("user"));
        String base = "/api/admin/conversation-history/";
        for (String path : List.of("overview", "users", "directory", "export")) {
            assertThat(request("GET", base + path, ordinary).statusCode()).isEqualTo(403);
            assertThat(request("GET", base + path, null).statusCode()).isEqualTo(401);
        }
        var response = request("GET", base + "overview?from=2026-11-06&to=2026-11-06", admin);
        assertThat(response.statusCode()).isEqualTo(200);
        var first = json.readTree(response.body());
        assertThat(first.path("totals").path("sessions").asInt()).isEqualTo(24);
        assertThat(first.path("totals").path("questions").asInt()).isEqualTo(26);
        assertThat(first.path("totals").path("users").asInt()).isEqualTo(2);
        assertThat(first.path("items").size()).isEqualTo(10);
        assertThat(first.path("items").get(0).path("sessionKey").asText()).isEqualTo("ov-23");
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        var second = json.readTree(request("GET", base + "overview?page=1", admin).body());
        assertThat(second.path("items").get(0).path("sessionKey").asText()).isEqualTo("ov-13");
        var sorted = json.readTree(request("GET", base + "overview?sort=questions", admin).body());
        assertThat(sorted.path("items").get(0).path("sessionKey").asText()).isEqualTo("ov-00");
        String keyword =
                java.net.URLEncoder.encode("正文关键字 100%", java.nio.charset.StandardCharsets.UTF_8);
        var search =
                json.readTree(request("GET", base + "overview?query=" + keyword, admin).body());
        assertThat(search.path("totals").path("sessions").asInt()).isEqualTo(1);
        assertThat(search.path("totals").path("questions").asInt()).isEqualTo(3);
        assertThat(
                        request("GET", base + "overview?query=" + keyword + "&userId=user-b", admin)
                                .body())
                .contains("\"sessions\":0");
        assertThat(
                        request(
                                        "GET",
                                        base
                                                + "overview?query="
                                                + java.net.URLEncoder.encode(
                                                        "只在回复出现",
                                                        java.nio.charset.StandardCharsets.UTF_8),
                                        admin)
                                .body())
                .contains("\"sessions\":0");
        assertThat(request("GET", base + "overview?query=user-a", admin).body())
                .contains("\"sessions\":0");
        assertThat(request("GET", base + "overview?to=2026-11-05", admin).body())
                .contains("\"sessions\":0");
        var perUser = json.readTree(request("GET", base + "users?query=" + keyword, admin).body());
        assertThat(perUser.path("items").size()).isEqualTo(1);
        assertThat(perUser.path("items").get(0).path("questions").asInt()).isEqualTo(3);
        String name = java.net.URLEncoder.encode("同名用户", java.nio.charset.StandardCharsets.UTF_8);
        var directory = json.readTree(request("GET", base + "directory?q=" + name, admin).body());
        assertThat(directory.size()).isEqualTo(2);
        assertThat(directory.toString())
                .contains("user-a", "user-b")
                .doesNotContain("password", "unused");
        var csv = request("GET", base + "export?query=" + keyword, admin);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body())
                .startsWith("\uFEFF")
                .contains("\"'=SUM(1,2) 标题\"", "同名用户", ",3,")
                .doesNotContain("一般问题", "只在回复出现");
        assertThat(request("GET", base + "overview?pageSize=200", admin).statusCode())
                .isEqualTo(400);
        assertThat(request("GET", base + "overview?sort=payload", admin).statusCode())
                .isEqualTo(400);
        assertThat(
                        request("GET", base + "overview?from=2026-11-07&to=2026-11-05", admin)
                                .statusCode())
                .isEqualTo(400);
        assertThat(request("GET", base + "overview?page=999", admin).body()).contains("\"page\":2");
        var old = message(10, "USER", "旧消息", time);
        old.id = "legacy";
        old.sessionKey = "ov-00";
        messages.save(old);
        assertThat(request("GET", base + "overview", admin).body()).contains("\"indexing\":true");
        var account = users.findById("overview-admin").orElseThrow();
        account.setRolesCsv("user");
        users.save(account);
        for (String path : List.of("overview", "users", "directory", "export"))
            assertThat(request("GET", base + path, admin).statusCode()).isEqualTo(403);
    }

    @Test
    void legacyMessageProjectionBackfillsWithoutResurrectingDeletedOrChangedMessages()
            throws Exception {
        addUser("backfill-admin", "管理员", "user,admin");
        long time = Instant.parse("2026-11-05T16:15:00Z").toEpochMilli();
        addSession("s1", "backfill-admin", "main", time);
        messages.save(message(0, "USER", "旧提问正文", time));
        messages.save(message(1, "ASSISTANT", "回复不作问题", time));
        String token = jwt.generate("backfill-admin", "backfill-admin", List.of("user", "admin"));
        String path = "/api/admin/conversation-history/overview";
        assertThat(request("GET", path, token).body()).contains("\"indexing\":true");
        assertThat(request("GET", "/api/admin/conversation-history/export", token).statusCode())
                .isEqualTo(409);
        var emptyRegistry = org.mockito.Mockito.mock(SessionRegistryRepository.class);
        org.mockito.Mockito.when(
                        emptyRegistry.findAll(
                                org.mockito.ArgumentMatchers.any(
                                        org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        var worker =
                new io.agentscope.dataagent.web.session.SessionHistoryService(
                        org.mockito.Mockito.mock(
                                io.agentscope.dataagent.runtime.DataAgentBootstrap.class),
                        emptyRegistry,
                        history,
                        messages,
                        transactionManager);
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "indexBatch");
        } finally {
            worker.stop();
        }
        var result = request("GET", path, token);
        assertThat(result.body()).contains("\"indexing\":false", "\"questions\":1");
        var projected = messages.findById("message-0").orElseThrow();
        assertThat(projected.messageRole).isEqualTo("USER");
        assertThat(projected.userText).isEqualTo("旧提问正文");
        assertThat(messages.findById("message-1").orElseThrow().userText).isNull();
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThat(
                        tx.<Integer>execute(
                                status ->
                                        messages.projectLegacy(
                                                "message-0", "old-payload", "USER", "wrong")))
                .isZero();
        messages.deleteById("message-0");
        assertThat(
                        tx.<Integer>execute(
                                status ->
                                        messages.projectLegacy(
                                                "message-0", projected.payload, "USER", "wrong")))
                .isZero();
        assertThat(messages.existsById("message-0")).isFalse();
    }

    @Test
    void csvQuotesValuesAndNeutralizesFormulaPrefixes() {
        assertThat(AdminHistoryOverviewController.cell("=1+2")).isEqualTo("\"'=1+2\"");
        assertThat(AdminHistoryOverviewController.cell("  =1+2")).isEqualTo("\"'  =1+2\"");
        assertThat(AdminHistoryOverviewController.cell("a,\"b\"\nnext"))
                .isEqualTo("\"a,\"\"b\"\"\nnext\"");
        assertThat(AdminHistoryOverviewController.cell(null)).isEqualTo("\"\"");
    }

    @Test
    void readsOnlySelectedUsersFinalRepliesAndRevokesPrivilegesOnExistingTokens() throws Exception {
        addUser("admin-one", "Owner", "user,admin");
        addUser("analyst", "测试人员", "user,analyst");
        addUser("colleague", "测试人员", "user");
        long time = Instant.parse("2026-11-05T16:15:00Z").toEpochMilli();
        addSession("s1", "analyst", "main", time);
        addSession("foreign", "colleague", "main", time);
        addSession("sub", "analyst", "sub", time);
        var header = new SessionHistoryEntity();
        header.sessionKey = "s1";
        header.title = "访问统计";
        header.activityMs = time;
        header.messageCount = 24;
        history.save(header);
        var artifact = new ArtifactEntity();
        artifact.id = "887c2cc1-84d5-49fc-b9a8-07de522ce002";
        artifact.ownerId = "analyst";
        artifact.sessionId = "s1";
        artifact.runId = "run";
        artifact.filename = "result.csv";
        artifact.sourcePath = "/workspace/result.csv";
        artifacts.save(artifact);

        var rows = new ArrayList<SessionMessageEntity>();
        rows.add(message(0, "USER", "老问题", time));
        rows.add(message(1, "ASSISTANT", "过程文字", time));
        rows.add(message(2, "TOOL", "工具原始行", time));
        rows.add(
                message(
                        3,
                        "ASSISTANT",
                        "正式答复 [下载](/api/artifacts/" + artifact.id + "/content)",
                        time));
        for (int i = 4; i < 24; i++) rows.add(message(i, "USER", "问题 " + i, time));
        messages.saveAll(rows);

        String roles = "/api/admin/administrators";
        String historyBase = "/api/admin/conversation-history";
        String list = historyBase + "/sessions";
        String detailBase = historyBase + "/analyst/messages?sessionKey=";
        String detail = detailBase + "s1";
        String admin = jwt.generate("admin-one", "admin-one", List.of("user", "admin"));
        String ordinary = jwt.generate("analyst", "analyst", List.of("user"));
        assertThat(request("GET", detail, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", detail, ordinary).statusCode()).isEqualTo(403);
        assertThat(request("GET", detailBase + "foreign", admin).statusCode()).isEqualTo(404);
        assertThat(request("GET", detailBase + "sub", admin).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/artifacts/" + artifact.id + "/content", admin).statusCode())
                .isEqualTo(404);
        assertThat(request("GET", roles + "/search-users?q=analyst", admin).body())
                .contains("测试人员", "analyst")
                .doesNotContain("password", "unused");
        var listing = request("GET", list + "?userId=analyst&from=2026-11-06&to=2026-11-06", admin);
        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(listing.body()).contains("访问统计").doesNotContain("foreign", "\"sub\"");
        assertThat(request("GET", list + "?userId=analyst&to=2026-11-05", admin).body())
                .contains("\"items\":[]");
        assertThat(request("GET", list + "?title=wrong", admin).body()).contains("\"items\":[]");
        var latest = request("GET", detail, admin);
        assertThat(latest.statusCode()).isEqualTo(200);
        assertThat(latest.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(json.readTree(latest.body()).path("items").size()).isEqualTo(20);
        int cursor = json.readTree(latest.body()).path("nextBefore").asInt();
        var earlier = request("GET", detail + "&before=" + cursor, admin);
        assertThat(earlier.body())
                .contains("老问题", "正式答复", "附件不可查看")
                .doesNotContain("过程文字", "工具原始行", artifact.id);

        assertThat(request("GET", list, ordinary).statusCode()).isEqualTo(403);
        var all = json.readTree(request("GET", list, admin).body()).path("items");
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.get(0).path("owner").path("username").asText()).isEqualTo("analyst");
        assertThat(all.get(1).path("owner").path("username").asText()).isEqualTo("colleague");
        for (int i = 10; i < 31; i++) addSession("recent-" + i, "colleague", "main", time + 1000);
        var firstPage = json.readTree(request("GET", list, admin).body());
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        assertThat(firstPage.path("items").size()).isEqualTo(20);
        assertThat(firstPage.path("items").get(0).path("sessionKey").asText())
                .isEqualTo("recent-30");
        var last = firstPage.path("items").get(19);
        var nextPage =
                json.readTree(
                        request(
                                        "GET",
                                        list
                                                + "?before="
                                                + last.path("lastActivityMs").asLong()
                                                + "&beforeKey="
                                                + last.path("sessionKey").asText(),
                                        admin)
                                .body());
        assertThat(nextPage.path("hasMore").asBoolean()).isFalse();
        assertThat(nextPage.path("items").findValuesAsText("sessionKey"))
                .containsExactly("recent-10", "s1", "foreign");

        assertThat(request("GET", roles, admin).body())
                .contains("admin-one")
                .doesNotContain("analyst");
        assertThat(request("DELETE", roles + "/admin-one", admin).statusCode()).isEqualTo(409);
        assertThat(request("PUT", roles + "/colleague", ordinary).statusCode()).isEqualTo(403);
        assertThat(request("PUT", roles + "/analyst", admin).statusCode()).isEqualTo(204);
        assertThat(request("PUT", roles + "/analyst", admin).statusCode()).isEqualTo(204);
        assertThat(users.findById("analyst").orElseThrow().getRolesCsv())
                .isEqualTo("user,analyst,admin");
        assertThat(request("GET", roles, ordinary).statusCode()).isEqualTo(403);
        String newAdmin = jwt.generate("analyst", "analyst", List.of("user", "analyst", "admin"));
        assertThat(request("GET", roles, newAdmin).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", roles + "/analyst", admin).statusCode()).isEqualTo(204);
        assertThat(users.findById("analyst").orElseThrow().getRolesCsv()).isEqualTo("user,analyst");
        assertThat(request("GET", detail, newAdmin).statusCode()).isEqualTo(403);
        assertThat(request("PUT", roles + "/analyst", newAdmin).statusCode()).isEqualTo(403);
    }

    private void addUser(String id, String name, String roles) {
        var user = new UserEntity(id, id, "unused", roles);
        user.setDisplayName(name);
        users.save(user);
    }

    private void addSession(String key, String owner, String kind, long time) {
        var session = new SessionRegistryEntity();
        session.setSessionKey(key);
        session.setSessionId(key);
        session.setKind(kind);
        session.setUserId(owner);
        session.setAgentId("data-agent");
        session.setLastActivityMs(time);
        registry.save(session);
    }

    private SessionMessageEntity message(int position, String role, String content, long time)
            throws Exception {
        var message = new SessionMessageEntity();
        message.id = "message-" + position;
        message.sessionKey = "s1";
        message.position = position;
        message.payload =
                json.writeValueAsString(
                        Map.of("role", role, "content", content, "timestampMs", time));
        return message;
    }

    private HttpResponse<String> request(String method, String path, String token)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
