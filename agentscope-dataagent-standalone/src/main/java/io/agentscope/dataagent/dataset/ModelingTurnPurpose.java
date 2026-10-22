package io.agentscope.dataagent.dataset;

/**
 * Why a modeling turn runs, as declared by the page that sent it (spec092). It labels draft
 * changes for review and gates two capabilities: only {@link #QUESTION} turns (generating,
 * retrying or repairing a user question's SQL) may query external-source tables, and
 * {@link #AUTO} turns may write business rules only when the knowledge base has a business
 * document to ground them. A missing purpose means the user typed the request in the chat.
 */
public record ModelingTurnPurpose(String value) {
    public static final String QUESTION = "question";
    public static final String AUTO = "auto";
    public static final String DOCUMENT = "document";

    /** The page hands the user's confirmation answers to the assistant to write down. */
    public static final String CONFIRM = "confirm";

    /** Draft-change source for a turn without a declared purpose: the user's own chat request. */
    public static final String CHAT = "chat";

    /** Draft-change source for documents the user uploads through the page, not via the agent. */
    public static final String UPLOAD = "upload";

    /** Draft-change source for the user's own edit in the review page. */
    public static final String EDIT = "edit";

    /** Accepts only known purposes so a client cannot invent new permissions. */
    public static String normalize(String value) {
        return QUESTION.equals(value)
                        || AUTO.equals(value)
                        || DOCUMENT.equals(value)
                        || CONFIRM.equals(value)
                ? value
                : null;
    }

    public boolean question() {
        return QUESTION.equals(value);
    }

    public boolean auto() {
        return AUTO.equals(value);
    }
}
