package app.interline.agent;

/** What {@link AgentConnection#receive()} yields: a control message, an audio chunk, or the end of the stream. */
public sealed interface Inbound permits ControlMessage, AudioChunk, Inbound.Closed {
    /** The transport ended: clean EOF, I/O failure, or a malformed frame that was answered with bad_frame. */
    record Closed(String reason) implements Inbound { }
}
