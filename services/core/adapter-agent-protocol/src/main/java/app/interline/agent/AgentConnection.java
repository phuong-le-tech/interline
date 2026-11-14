package app.interline.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One end of an Agent Protocol stream (the core over a child process pipes, or the agent over its own stdio).
 * Malformed frames are answered with {@code error{code:"bad_frame"}} and the transport is closed.
 */
public final class AgentConnection implements Closeable {
    private static final int DEFAULT_WINDOW = 50;
    private final FrameCodec codec = new FrameCodec();
    private final BufferedInputStream input;
    private final OutputStream output;
    private final Object writeLock = new Object();
    private volatile ControlMessage hello;

    public AgentConnection(InputStream input, OutputStream output) {
        this.input = new BufferedInputStream(input);
        this.output = output;
    }

    /** Never throws: the end of the stream, an I/O failure or a bad frame all surface as {@link Inbound.Closed}. */
    public Inbound receive() {
        try {
            Frame frame = codec.read(input);
            if (frame == null) return new Inbound.Closed("eof");
            return frame.kind() == FrameCodec.KIND_CONTROL
                    ? codec.decodeControl(frame.payload())
                    : codec.decodeAudio(frame.payload());
        } catch (BadFrameException e) {
            rejectBadFrame(e.getMessage());
            return new Inbound.Closed("bad_frame");
        } catch (IOException e) {
            return new Inbound.Closed("io_error");
        }
    }

    /** True when another frame can be read without blocking on the transport. */
    public boolean hasBufferedInput() {
        try {
            return input.available() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    public void send(ControlMessage message) throws IOException {
        byte[] payload = codec.encodeControl(message);
        synchronized (writeLock) { codec.write(output, new Frame(FrameCodec.KIND_CONTROL, payload)); }
    }

    public void send(AudioChunk chunk) throws IOException {
        byte[] payload = codec.encodeAudio(chunk);
        synchronized (writeLock) { codec.write(output, new Frame(FrameCodec.KIND_AUDIO, payload)); }
    }

    /** The agent's hello, available after {@link #handshake}. */
    public ControlMessage hello() { return hello; }

    /** Read the agent hello, answer with the negotiated ack or a reject, and return what was sent. */
    public ControlMessage handshake(int supportedMin, int supportedMax, String coreVersion) throws IOException {
        if (!(receive() instanceof ControlMessage received) || !"hello".equals(received.type())) {
            throw new IOException("expected hello as the first message");
        }
        hello = received;
        JsonNode protocol = received.body().path("protocol");
        int agentMin = protocol.path("min").asInt(Integer.MAX_VALUE);
        int agentMax = protocol.path("max").asInt(0);
        int chosen = Math.min(supportedMax, agentMax);
        ControlMessage reply;
        if (chosen >= Math.max(supportedMin, agentMin)) {
            ObjectNode body = ControlMessage.nodes().objectNode().put("protocol", chosen).put("core_version", coreVersion);
            body.putObject("session_defaults").put("window", DEFAULT_WINDOW);
            reply = ControlMessage.response(received.id(), "hello.ack", body);
        } else {
            ObjectNode body = ControlMessage.nodes().objectNode().put("reason", "protocol_version_unsupported");
            body.putObject("core_supports").put("min", supportedMin).put("max", supportedMax);
            reply = ControlMessage.response(received.id(), "hello.reject", body);
        }
        send(reply);
        return reply;
    }

    private void rejectBadFrame(String detail) {
        ObjectNode body = ControlMessage.nodes().objectNode().put("code", "bad_frame").put("severity", "fatal")
                .put("retryable", true).put("message", "Invalid frame: " + detail);
        try {
            send(ControlMessage.event("error", body));
        } catch (IOException ignored) {
            // The peer may already be gone; closing below is the only remaining action either way.
        }
        close();
    }

    @Override public void close() {
        try { output.close(); } catch (IOException ignored) { /* best effort */ }
        try { input.close(); } catch (IOException ignored) { /* best effort */ }
    }
}
