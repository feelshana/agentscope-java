package io.agentscope.dataagent.runtime.wren;

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway.WrenCallResult;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes connection diagnostics, never arbitrary SQL text or successful query data. */
final class WrenConnectionFailure {
    private static final Set<String> READ_TOOLS = Set.of("run_sql", "dry_run", "query_cube");
    private static final Pattern CONNECTION =
            Pattern.compile(
                    "^Error executing tool (run_sql|dry_run|query_cube):"
                        + " (?:\\[GENERIC_USER_ERROR\\] )?\\((?:2006, ['\"](?:MySQL )?[Ss]erver has"
                        + " gone away|(?:2013|2055), ['\"]Lost connection to (?:MySQL"
                        + " )?server|(?:2002|2003), ['\"](?:Can't connect to (?:MySQL"
                        + " )?server|Connection refused)).*",
                    Pattern.DOTALL);

    static boolean readTool(String tool) {
        return READ_TOOLS.contains(tool);
    }

    static boolean matches(String tool, WrenCallResult result) {
        if (result.ok() || !readTool(tool) || result.payload() == null) return false;
        var match = CONNECTION.matcher(result.payload());
        return match.matches() && tool.equals(match.group(1));
    }

    private WrenConnectionFailure() {}
}
