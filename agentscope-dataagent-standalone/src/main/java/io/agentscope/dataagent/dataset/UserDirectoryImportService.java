package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Imports only selected profile fields, preserving platform identities and privileges. */
@Service
public class UserDirectoryImportService {
    private final UserEntityRepository users;
    private final UserStore store;
    private final KnowledgeBaseAccessService access;

    public UserDirectoryImportService(
            UserEntityRepository users, UserStore store, KnowledgeBaseAccessService access) {
        this.users = users;
        this.store = store;
        this.access = access;
    }

    public record Row(String username, String displayName, String company, String department) {}

    public record Result(int total, int created, int updated, boolean dryRun) {}

    @Transactional
    public Result apply(String actor, List<Row> rows, boolean dryRun) {
        if (!access.isAdmin(actor)) throw new DatasetException("仅全局管理员可导入用户目录", 403);
        if (rows == null || rows.isEmpty() || rows.size() > 5000)
            throw new DatasetException("每批导入1至5000条资料", 400);
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            if (row == null
                    || row.username() == null
                    || !row.username().matches("[\\p{L}\\p{N}_.@-]{1,191}"))
                throw new DatasetException("存在空白或非法账号，未导入", 400);
            if (!seen.add(row.username().toLowerCase(Locale.ROOT)))
                throw new DatasetException("存在重复账号，未导入", 400);
            for (String value : Arrays.asList(row.displayName(), row.company(), row.department()))
                if (value != null
                        && (value.length() > 191
                                || value.chars().anyMatch(Character::isISOControl)))
                    throw new DatasetException("资料字段过长或含控制字符，未导入", 400);
        }
        int created = 0, updated = 0;
        for (Row row : rows) {
            var found = users.findByUsernameIgnoreCase(row.username());
            if (found.isPresent()) updated++;
            else created++;
            if (dryRun) continue;
            if (found.isEmpty()) {
                store.createUser(
                        UUID.randomUUID().toString(),
                        row.username(),
                        UUID.randomUUID().toString() + UUID.randomUUID(),
                        List.of("user"));
                found = users.findByUsernameIgnoreCase(row.username());
            }
            UserEntity user = found.orElseThrow();
            user.setDisplayName(clean(row.displayName()));
            user.setCompany(clean(row.company()));
            user.setDepartment(clean(row.department()));
            users.save(user);
        }
        return new Result(rows.size(), created, updated, dryRun);
    }

    private static String clean(String value) {
        return value == null ? null : value.strip();
    }
}
