package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/auth/profile")
public class UserProfileController {
    private final UserEntityRepository users;

    public UserProfileController(UserEntityRepository users) {
        this.users = users;
    }

    public record Profile(String username, String displayName, String company, String department) {}

    @GetMapping
    public Mono<Profile> get(Authentication auth) {
        return Mono.fromCallable(
                        () ->
                                users.findById((String) auth.getPrincipal())
                                        .map(
                                                u ->
                                                        new Profile(
                                                                u.getUsername(),
                                                                u.getDisplayName(),
                                                                u.getCompany(),
                                                                u.getDepartment()))
                                        .orElseThrow(
                                                () ->
                                                        new org.springframework.web.server
                                                                .ResponseStatusException(
                                                                org.springframework.http.HttpStatus
                                                                        .UNAUTHORIZED)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
