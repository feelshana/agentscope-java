package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.JpaUserStore;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/administrators")
public class GlobalAdministratorController {
    private final UserEntityRepository users;
    private final UserStore userStore;

    public GlobalAdministratorController(UserEntityRepository users, UserStore userStore) {
        this.users = users;
        this.userStore = userStore;
    }

    @GetMapping
    public Mono<List<UserView>> list(Authentication auth) {
        AdminUserController.requireAdmin(auth);
        return Mono.fromCallable(
                        () ->
                                users.findAdministratorCandidates().stream()
                                        .filter(JpaUserStore::isAdministrator)
                                        .map(UserView::of)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/search-users")
    public Mono<List<UserView>> search(@RequestParam String q, Authentication auth) {
        AdminUserController.requireAdmin(auth);
        String term = q.strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return Mono.just(List.of());
        if (term.length() > 191) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索词过长");
        }
        return Mono.fromCallable(
                        () ->
                                users
                                        .searchAdminDirectory(
                                                term,
                                                PageRequest.of(
                                                        0, 20, Sort.by("username", "userId")))
                                        .stream()
                                        .map(UserView::of)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> grant(@PathVariable String userId, Authentication auth) {
        return change(userId, true, auth);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revoke(@PathVariable String userId, Authentication auth) {
        return change(userId, false, auth);
    }

    private Mono<Void> change(String target, boolean enabled, Authentication auth) {
        AdminUserController.requireAdmin(auth);
        return Mono.<Void>fromRunnable(
                        () -> {
                            try {
                                if (!userStore.setAdministrator(
                                        (String) auth.getPrincipal(), target, enabled)) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "找不到该用户");
                                }
                            } catch (IllegalStateException e) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, e.getMessage());
                            } catch (SecurityException e) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, e.getMessage());
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record UserView(String userId, String username, String displayName, String department) {
        private static UserView of(UserEntity user) {
            return new UserView(
                    user.getUserId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getDepartment());
        }
    }
}
