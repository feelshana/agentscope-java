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
package io.agentscope.dataagent.web.auth;

import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for authentication endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} — authenticate with username/password, returns JWT
 *   <li>{@code GET /api/auth/me} — returns current user info (requires JWT)
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserStore userStore;
    private final JwtService jwtService;
    private final Optional<Model> model;
    private final AuthSettings settings;
    private final BiLoginService biLogin;

    public AuthController(
            UserStore userStore,
            JwtService jwtService,
            Optional<Model> modelOpt,
            AuthSettings settings,
            BiLoginService biLogin) {
        this.userStore = userStore;
        this.jwtService = jwtService;
        this.model = modelOpt;
        this.settings = settings;
        this.biLogin = biLogin;
    }

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(
            String token, String userId, String username, List<String> roles, long expiresAt) {}

    public record EntryOptions(
            boolean passwordLoginEnabled, boolean biLoginEnabled, boolean biAccountLoginEnabled) {}

    public record BiLoginRequest(String ticket) {}

    @GetMapping("/options")
    public EntryOptions options() {
        return new EntryOptions(
                settings.passwordLogin(), settings.biLogin(), settings.accountLogin());
    }

    private LoginResponse issue(UserStore.UserRecord user) {
        String token = jwtService.generate(user.userId(), user.username(), user.roles());
        return new LoginResponse(
                token,
                user.userId(),
                user.username(),
                user.roles(),
                jwtService.parse(token).getExpiration().getTime());
    }

    @PostMapping("/bi-login")
    public Mono<LoginResponse> biLogin(@RequestBody BiLoginRequest req) {
        if (!settings.biLogin())
            return Mono.error(
                    new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.NOT_FOUND));
        return Mono.fromCallable(() -> issue(biLogin.login(req.ticket())))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    public record AccountLoginRequest(String username) {}

    @PostMapping("/bi-account-login")
    public Mono<LoginResponse> accountLogin(@RequestBody AccountLoginRequest request) {
        if (!settings.accountLogin())
            return Mono.error(
                    new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.NOT_FOUND));
        return Mono.fromCallable(() -> issue(biLogin.account(request.username())))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    public record MeResponse(
            String userId,
            String username,
            List<String> roles,
            Boolean aiAvailable,
            boolean isAdmin) {}

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody LoginRequest req) {
        if (!settings.passwordLogin())
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        return Mono.fromCallable(
                        () -> {
                            if (req.username() == null || req.password() == null) {
                                return ResponseEntity.badRequest().<LoginResponse>build();
                            }
                            return userStore
                                    .findByUsername(req.username())
                                    .filter(u -> userStore.verifyPassword(u, req.password()))
                                    .map(
                                            u -> {
                                                return ResponseEntity.ok(issue(u));
                                            })
                                    .orElse(
                                            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                                    .<LoginResponse>build());
                        })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @GetMapping("/me")
    public Mono<MeResponse> me(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                userStore
                                        .findById(userId)
                                        .map(
                                                u ->
                                                        new MeResponse(
                                                                u.userId(),
                                                                u.username(),
                                                                u.roles(),
                                                                aiAvailable(),
                                                                u.hasRole("admin")))
                                        .orElse(
                                                new MeResponse(
                                                        userId,
                                                        userId,
                                                        List.of("user"),
                                                        aiAvailable(),
                                                        false)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private boolean aiAvailable() {
        return model.map(
                        value ->
                                value
                                                instanceof
                                                io.agentscope.dataagent.runtime.model.SwitchingModel
                                                        switching
                                        ? switching.isAvailable()
                                        : true)
                .orElse(false);
    }
}
