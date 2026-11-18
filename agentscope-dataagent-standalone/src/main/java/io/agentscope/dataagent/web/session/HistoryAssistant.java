package io.agentscope.dataagent.web.session;

/** Business classification from server-owned routing metadata, never from conversation text. */
public enum HistoryAssistant {
    QUERY("query", "问数助手", "data-agent"),
    MODELING("modeling", "建模助手", "modeling-agent"),
    DASHBOARD("dashboard", "仪表盘助手", "dashboard-agent"),
    OTHER("other", "其他助手", null);

    public final String code;
    public final String label;
    private final String routingId;

    HistoryAssistant(String code, String label, String routingId) {
        this.code = code;
        this.label = label;
        this.routingId = routingId;
    }

    public static HistoryAssistant resolve(String gateKey, String storedAgentId) {
        String route = SessionHistoryService.segment(gateKey, "|x:agentId=");
        String id = route == null ? storedAgentId : route;
        for (var type : values()) {
            if (type.routingId != null && type.routingId.equals(id)) return type;
        }
        return OTHER;
    }

    public static boolean validFilter(String value) {
        return "all".equals(value)
                || QUERY.code.equals(value)
                || MODELING.code.equals(value)
                || DASHBOARD.code.equals(value);
    }

    public static String label(String code) {
        for (var type : values()) if (type.code.equals(code)) return type.label;
        return OTHER.label;
    }

    /** Same precedence and exact delimiter matching as resolve, portable to MySQL and H2. */
    public static String sql(String alias) {
        String gate = "coalesce(" + alias + ".gate_key,'')";
        StringBuilder result = new StringBuilder("case");
        for (var type : values()) {
            if (type.routingId == null) continue;
            result.append(" when (locate('|x:agentId=")
                    .append(type.routingId)
                    .append("|',concat(")
                    .append(gate)
                    .append(",'|'))>0 or (locate('|x:agentId=',")
                    .append(gate)
                    .append(")=0 and ")
                    .append(alias)
                    .append(".agent_id='")
                    .append(type.routingId)
                    .append("')) then '")
                    .append(type.code)
                    .append("'");
        }
        return result.append(" else 'other' end").toString();
    }
}
