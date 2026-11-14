package app.interline.domain;

/** Identity of the connected capture agent, from its hello message. */
public record AgentInfo(String name, String version, int protocol) { }
