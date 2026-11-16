package app.interline.domain;

/** Identity of the connected capture agent, from its hello message. */
public record AgentInfo(String name, String version, int protocol, boolean echoCancellation, String sttEngine) {
    /** An agent that advertises neither echo cancellation nor a speech engine. */
    public AgentInfo(String name, String version, int protocol) { this(name, version, protocol, false, "none"); }
}
