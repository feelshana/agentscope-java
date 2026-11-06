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
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

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
