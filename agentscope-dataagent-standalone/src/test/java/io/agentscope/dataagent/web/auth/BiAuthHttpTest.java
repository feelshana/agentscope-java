package io.agentscope.dataagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.web.config.SecurityConfig;
import io.agentscope.dataagent.web.persistence.jpa.BiLoginTicketEntity;
import io.agentscope.dataagent.web.persistence.jpa.BiLoginTicketRepository;
import io.agentscope.dataagent.web.persistence.jpa.JpaUserStore;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
        classes = BiAuthHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:bi-http;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never",
            "dataagent.jwt.secret=test-http-signing-key-at-least-32-bytes",
            "dataagent.auth.bi.shared-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class BiAuthHttpTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BiLoginTicketEntity.class)
    @EnableJpaRepositories(basePackageClasses = BiLoginTicketRepository.class)
    @Import({
        AuthSettings.class,
        AuthController.class,
        JwtService.class,
        BiTicketCodec.class,
        BiLoginService.class,
        SecurityConfig.class
    })
    static class App {
        @Bean
        UserStore users(UserEntityRepository repository) {
            return new JpaUserStore(repository, false);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired JwtService jwt;
    @Autowired UserStore users;
    final HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void scheduledTaskEndpointsAreClosedForEveryRole() throws Exception {
        for (var roles : List.of(List.of("user"), List.of("user", "admin"))) {
            String token = jwt.generate("closed-task-user", "closed-task-user", roles);
            for (String path :
                    List.of(
                            "/api/scheduled-tasks",
                            "/api/scheduled-tasks/old-task",
                            "/api/scheduled-tasks/old-task/status")) {
                for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                    var request =
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                    .header("Authorization", "Bearer " + token)
                                    .header("Content-Type", "application/json")
                                    .method(method, HttpRequest.BodyPublishers.ofString("{}"));
                    assertThat(
                                    client.send(
                                                    request.build(),
                                                    HttpResponse.BodyHandlers.ofString())
                                            .statusCode())
                            .isEqualTo(403);
                }
            }
        }
    }

    @Test
    void productionValidationResponsesKeepTheActionableReasonWithoutStackTraces() throws Exception {
        var response = post("/api/auth/bi-login", "{\"ticket\":\"bad\"}");
        assertThat(response.statusCode()).isEqualTo(401);
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertThat(body.path("message").asText()).contains("免登验证失败");
        assertThat(response.body()).doesNotContain("BiTicketCodec", "java.lang.", "\"trace\"");
    }

    @Test
    void prodEntryPolicyAndActualHttpAuthentication() throws Exception {
        var options = get("/api/auth/options", null);
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(options.body())
                .contains("\"passwordLoginEnabled\":false", "\"biLoginEnabled\":true");
        assertThat(options.headers().firstValue("cache-control")).contains("no-store");
        assertThat(options.headers().firstValue("x-frame-options")).contains("SAMEORIGIN");
        assertThat(
                        post("/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin\"}")
                                .statusCode())
                .isEqualTo(403);
        assertThat(post("/api/auth/bi-login", "{\"ticket\":\"bad\"}").statusCode()).isEqualTo(401);
        String ticket =
                BiTicketCodecTest.ticket(
                        "first-http-user",
                        System.currentTimeMillis(),
                        java.util.UUID.randomUUID().toString());
        String body = "{\"ticket\":\"" + ticket + "\"}";
        var logged = post("/api/auth/bi-login", body);
        assertThat(logged.statusCode()).isEqualTo(200);
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(logged.body());
        assertThat(result.path("roles").toString()).isEqualTo("[\"user\"]");
        assertThat(get("/api/auth/me", result.path("token").asText()).statusCode()).isEqualTo(200);
        assertThat(post("/api/auth/bi-login", body).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", null).statusCode()).isEqualTo(401);
        var user = users.createUser("http-user", "http-user", "unused", List.of("user"));
        String token = jwt.generate(user.userId(), user.username(), user.roles());
        var me = get("/api/auth/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("http-user");
    }
}
