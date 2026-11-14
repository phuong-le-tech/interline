package app.interline.agent;

/** Frame kind and bytes following the kind byte. */
public record Frame(int kind, byte[] payload) { }
