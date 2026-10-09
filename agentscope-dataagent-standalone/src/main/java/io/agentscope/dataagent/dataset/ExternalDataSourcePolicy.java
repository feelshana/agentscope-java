package io.agentscope.dataagent.dataset;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validates user-managed connections before JDBC drivers or external runtimes see a URL. */
@Component
public class ExternalDataSourcePolicy {
    private final Set<String> allowedEndpoints;

    public ExternalDataSourcePolicy(
            @Value("${dataagent.external-datasource.allowed-endpoints:}") String endpoints) {
        allowedEndpoints =
                Arrays.stream(endpoints.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    public String build(String kind, String host, Integer port, String database, String sslMode) {
        if (host == null || !host.matches("[a-zA-Z0-9.\\-]+|\\[[0-9a-fA-F:]+\\]")) {
            throw invalid("数据库主机格式不正确");
        }
        String db = database == null ? "" : database;
        if (!db.matches("[a-zA-Z0-9_-]*")) throw invalid("数据库名仅支持字母、数字、下划线和短横线");
        if (sslMode != null
                && !sslMode.isBlank()
                && !allowedParameter(
                        kind, "postgresql".equals(kind) ? "sslmode" : "sslMode", sslMode)) {
            throw invalid("连接加密选项不正确");
        }
        String parameter = "postgresql".equals(kind) ? "sslmode" : "sslMode";
        return normalize(
                kind,
                "jdbc:"
                        + kind
                        + "://"
                        + host
                        + ":"
                        + (port == null ? ("postgresql".equals(kind) ? 5432 : 3306) : port)
                        + "/"
                        + db
                        + (sslMode == null || sslMode.isBlank()
                                ? ""
                                : "?" + parameter + "=" + sslMode));
    }

    public String normalize(String kind, String jdbcUrl) {
        if (!Set.of("mysql", "postgresql").contains(kind == null ? "" : kind)) {
            throw invalid("仅支持 MySQL 或 PostgreSQL 数据源");
        }
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:" + kind + "://")) {
            throw invalid("连接地址与数据库类型不匹配");
        }
        try {
            URI uri = URI.create(jdbcUrl.substring(5));
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? (kind.equals("mysql") ? 3306 : 5432) : uri.getPort();
            String path = uri.getRawPath();
            if (host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || port < 1
                    || port > 65535
                    || path == null
                    || !path.matches("/?[a-zA-Z0-9_-]*")) {
                throw invalid("连接地址必须是单一主机、端口和数据库名，不能包含凭证或额外指令");
            }
            String endpoint = host.toLowerCase(Locale.ROOT) + ":" + port;
            if (!allowedEndpoints.isEmpty() && !allowedEndpoints.contains(endpoint)) {
                throw new DatasetException(
                        "该数据库地址未获管理员允许，请配置 external-datasource.allowed-endpoints", 403);
            }
            Map<String, String> params = new TreeMap<>();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                for (String pair : uri.getRawQuery().split("&", -1)) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length != 2) throw invalid("连接参数格式不正确");
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (!allowedParameter(kind, key, value)
                            || params.putIfAbsent(key, value) != null) {
                        throw invalid("不允许的连接参数或重复参数：" + key);
                    }
                }
            }
            if (kind.equals("mysql")
                    && params.containsKey("sslMode")
                    && (params.containsKey("useSSL")
                            || params.containsKey("requireSSL")
                            || params.containsKey("verifyServerCertificate"))) {
                throw invalid("sslMode 不能与旧版 SSL 参数同时使用");
            }
            params.put("connectTimeout", kind.equals("mysql") ? "10000" : "10");
            params.put("socketTimeout", kind.equals("mysql") ? "30000" : "30");
            if (kind.equals("mysql")) {
                params.put("allowLoadLocalInfile", "false");
                params.put("allowUrlInLocalInfile", "false");
                params.put("autoDeserialize", "false");
            }
            String query =
                    params.entrySet().stream()
                            .map(
                                    e ->
                                            e.getKey()
                                                    + "="
                                                    + URLEncoder.encode(
                                                            e.getValue(), StandardCharsets.UTF_8))
                            .collect(Collectors.joining("&"));
            return "jdbc:" + kind + "://" + endpoint + (path.isEmpty() ? "/" : path) + "?" + query;
        } catch (IllegalArgumentException e) {
            throw invalid("连接地址格式不正确");
        }
    }

    private static boolean allowedParameter(String kind, String key, String value) {
        if (key.equals("connectTimeout"))
            return value.equals(kind.equals("mysql") ? "10000" : "10");
        if (key.equals("socketTimeout")) return value.equals(kind.equals("mysql") ? "30000" : "30");
        if (kind.equals("postgresql")) {
            return key.equals("sslmode")
                    && Set.of("disable", "require", "verify-ca", "verify-full").contains(value);
        }
        return switch (key) {
            case "useSSL",
                    "requireSSL",
                    "verifyServerCertificate",
                    "useUnicode",
                    "allowPublicKeyRetrieval" ->
                    value.equals("true") || value.equals("false");
            case "allowLoadLocalInfile", "allowUrlInLocalInfile", "autoDeserialize" ->
                    value.equals("false");
            case "sslMode" ->
                    Set.of("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY")
                            .contains(value);
            case "characterEncoding" -> value.matches("[a-zA-Z0-9_-]{1,32}");
            case "serverTimezone" -> value.matches("[a-zA-Z0-9_+:/-]{1,64}");
            default -> false;
        };
    }

    private static DatasetException invalid(String message) {
        return new DatasetException(message, 400);
    }
}
