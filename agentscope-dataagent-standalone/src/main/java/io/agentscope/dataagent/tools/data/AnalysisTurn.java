package io.agentscope.dataagent.tools.data;

/**
 * The analysis method the user chose for this query turn (spec097), carried on the
 * RuntimeContext. Without {@code confirmedParams} the turn only proposes parameters for the user to
 * confirm; queries run only after the user confirmed them in the card.
 *
 * @param method one of {@link AnalysisSkills#IDS}
 * @param confirmedParams the parameters the user confirmed, as JSON; {@code null} before that
 */
public record AnalysisTurn(String method, String confirmedParams) {
    public boolean confirmed() {
        return confirmedParams != null && !confirmedParams.isBlank();
    }

    public String name() {
        return AnalysisSkills.NAMES.getOrDefault(method, method);
    }
}
