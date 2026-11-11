package io.agentscope.dataagent.web.session;

import io.agentscope.dataagent.web.api.AdminConversationController.HistoryOwner;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-only admin projection. Counts and pages use the same predicate and read snapshot. */
@Service
public class AdminHistoryQueryService {
    private static final String ACTIVITY = "coalesce(h.activity_ms,s.last_activity_ms)";
    private static final String TITLE = "coalesce(h.title,s.label,'')";
    private static final String FROM =
            """
            from session_registry s
            left join session_history h on h.session_key=s.session_key
            left join dataagent_user u on u.user_id=s.user_id
            left join (select session_key,count(*) question_count from session_message
                       where message_role='USER' group by session_key) q on q.session_key=s.session_key
            """;
    private static final String SELECT =
            "select s.session_key,s.agent_id,"
                    + TITLE
                    + " title,"
                    + ACTIVITY
                    + " activity,coalesce(q.question_count,0)"
                    + " question_count,s.user_id,coalesce(u.username,s.user_id)"
                    + " username,u.display_name,u.department ";
    private final JdbcTemplate jdbc;

    public AdminHistoryQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(
            String userId, String query, long start, long end, String sort, boolean ascending) {}

    public record Session(
            String sessionKey,
            String agentId,
            String title,
            long lastActivityMs,
            long questionCount,
            HistoryOwner owner) {}

    public record Totals(long sessions, long questions, long users) {}

    public record Page(
            List<Session> items, Totals totals, int page, int pageSize, boolean indexing) {}

    public record UserTotal(HistoryOwner owner, long sessions, long questions) {}

    public record Users(List<UserTotal> items, boolean hasMore) {}

    @Transactional(readOnly = true)
    public List<HistoryOwner> directory(String term) {
        return jdbc.query(
                "select user_id,username,display_name,department from dataagent_user where"
                    + " locate(?,lower(username))>0 or locate(?,lower(coalesce(display_name,'')))>0"
                    + " order by username,user_id limit 20",
                (rs, i) -> owner(rs),
                term,
                term);
    }

    private record Predicate(String sql, List<Object> args) {}

    private Predicate predicate(Filter f) {
        var args = new ArrayList<Object>();
        String sql =
                " where s.kind='main' and s.user_id is not null and s.user_id<>'' and "
                        + ACTIVITY
                        + ">=? and "
                        + ACTIVITY
                        + "<?";
        args.add(f.start());
        args.add(f.end());
        if (f.userId() != null && !f.userId().isBlank()) {
            sql += " and s.user_id=?";
            args.add(f.userId());
        }
        if (!f.query().isBlank()) {
            sql +=
                    " and (locate(?,lower("
                            + TITLE
                            + "))>0 or exists (select 1 from session_message m where"
                            + " m.session_key=s.session_key and m.message_role='USER' and"
                            + " locate(?,lower(m.user_text))>0))";
            String term = f.query().strip().toLowerCase(Locale.ROOT);
            args.add(term);
            args.add(term);
        }
        return new Predicate(sql, args);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page page(Filter filter, int page, int size) {
        var p = predicate(filter);
        Totals totals =
                jdbc.queryForObject(
                        "select"
                            + " count(*),coalesce(sum(coalesce(q.question_count,0)),0),count(distinct"
                            + " s.user_id) "
                                + FROM
                                + p.sql(),
                        (rs, i) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                        p.args().toArray());
        int actualPage = Math.min(page, (int) Math.max(0, (totals.sessions() - 1) / size));
        var args = new ArrayList<>(p.args());
        args.add(size);
        args.add((long) actualPage * size);
        var rows =
                jdbc.query(
                        SELECT + FROM + p.sql() + order(filter) + " limit ? offset ?",
                        SESSION,
                        args.toArray());
        return new Page(rows, totals, actualPage, size, indexing());
    }

    @Transactional(readOnly = true)
    public Users users(Filter filter, int page) {
        var p = predicate(filter);
        var args = new ArrayList<>(p.args());
        args.add(21);
        args.add((long) page * 20);
        var rows =
                jdbc.query(
                        "select s.user_id,coalesce(u.username,s.user_id)"
                                + " username,u.display_name,u.department,count(*)"
                                + " session_count,coalesce(sum(coalesce(q.question_count,0)),0)"
                                + " questions "
                                + FROM
                                + p.sql()
                                + " group by s.user_id,u.username,u.display_name,u.department order"
                                + " by questions desc,s.user_id asc limit ? offset ?",
                        (rs, i) ->
                                new UserTotal(
                                        owner(rs),
                                        rs.getLong("session_count"),
                                        rs.getLong("questions")),
                        args.toArray());
        return new Users(rows.stream().limit(20).toList(), rows.size() > 20);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Session> export(Filter filter) {
        if (indexing())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "旧会话索引正在整理，请稍后导出");
        var p = predicate(filter);
        return jdbc.query(
                SELECT + FROM + p.sql() + order(filter) + " limit 5001",
                SESSION,
                p.args().toArray());
    }

    private boolean indexing() {
        return !jdbc.query(
                        "select id from session_message where message_role is null limit 1",
                        (rs, i) -> rs.getString(1))
                .isEmpty();
    }

    private String order(Filter f) {
        return " order by "
                + ("questions".equals(f.sort()) ? "coalesce(q.question_count,0)" : ACTIVITY)
                + (f.ascending() ? " asc" : " desc")
                + ",s.session_key desc";
    }

    private static HistoryOwner owner(ResultSet rs) throws SQLException {
        return new HistoryOwner(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("department"));
    }

    private static final RowMapper<Session> SESSION =
            (rs, i) ->
                    new Session(
                            rs.getString("session_key"),
                            rs.getString("agent_id"),
                            io.agentscope.dataagent.web.api.AdminConversationController.publicText(
                                    rs.getString("title")),
                            rs.getLong("activity"),
                            rs.getLong("question_count"),
                            owner(rs));
}
