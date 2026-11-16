package app.interline.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Agent Protocol v1 framing: {@code u32 length (big-endian, kind byte included) | u8 kind | payload}.
 * Kind 1 is a UTF-8 JSON control message (max 1 MiB), kind 2 a binary audio chunk (max 64 KiB).
 */
public final class FrameCodec {
    public static final int KIND_CONTROL = 1;
    public static final int KIND_AUDIO = 2;
    public static final int MAX_CONTROL_LENGTH = 1 << 20;
    public static final int MAX_AUDIO_LENGTH = 1 << 16;
    public static final int AUDIO_HEADER_BYTES = 24;
    private static final int LENGTH_PREFIX_BYTES = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Returns null on a clean end of stream before a frame starts; throws {@link BadFrameException} otherwise. */
    public Frame read(InputStream input) throws IOException {
        byte[] prefix = input.readNBytes(LENGTH_PREFIX_BYTES);
        if (prefix.length == 0) return null;
        if (prefix.length < LENGTH_PREFIX_BYTES) throw new BadFrameException("truncated length prefix");
        int length = ByteBuffer.wrap(prefix).getInt();
        if (length < 1 || length > MAX_CONTROL_LENGTH) throw new BadFrameException("frame length out of range: " + length);
        int kind = input.read();
        if (kind < 0) throw new BadFrameException("missing frame kind");
        int limit = switch (kind) {
            case KIND_CONTROL -> MAX_CONTROL_LENGTH;
            case KIND_AUDIO -> MAX_AUDIO_LENGTH;
            default -> throw new BadFrameException("unknown frame kind " + kind);
        };
        if (length > limit) throw new BadFrameException("frame length " + length + " over limit " + limit + " for kind " + kind);
        byte[] payload = input.readNBytes(length - 1);
        if (payload.length != length - 1) throw new BadFrameException("truncated frame payload");
        return new Frame(kind, payload);
    }

    public void write(OutputStream output, Frame frame) throws IOException {
        int length = frame.payload().length + 1;
        int limit = frame.kind() == KIND_AUDIO ? MAX_AUDIO_LENGTH : MAX_CONTROL_LENGTH;
        if (length > limit) throw new IOException("frame length " + length + " over limit " + limit);
        ByteBuffer wire = ByteBuffer.allocate(LENGTH_PREFIX_BYTES + length);
        wire.putInt(length).put((byte) frame.kind()).put(frame.payload());
        output.write(wire.array());
        output.flush();
    }

    public ControlMessage decodeControl(byte[] payload) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new BadFrameException("control payload is not JSON", e);
        }
        if (root == null || !root.isObject() || !root.path("t").isTextual()) {
            throw new BadFrameException("control payload is not an envelope with a type");
        }
        JsonNode body = root.path("body");
        if (!body.isObject()) throw new BadFrameException("control payload has no body object");
        return new ControlMessage(
                root.path("v").asInt(ControlMessage.ENVELOPE_VERSION),
                root.path("t").asText(),
                root.hasNonNull("id") ? root.path("id").asText() : null,
                root.hasNonNull("re") ? root.path("re").asText() : null,
                root.path("ts").asLong(0),
                body);
    }

    public byte[] encodeControl(ControlMessage message) throws IOException {
        ObjectNode envelope = JSON.createObjectNode().put("v", message.version()).put("t", message.type());
        if (message.id() != null) envelope.put("id", message.id());
        if (message.replyTo() != null) envelope.put("re", message.replyTo());
        envelope.put("ts", message.timestamp());
        envelope.set("body", message.body() == null ? JSON.createObjectNode() : message.body());
        return JSON.writeValueAsBytes(envelope);
    }

    public AudioChunk decodeAudio(byte[] payload) throws IOException {
        if (payload.length < AUDIO_HEADER_BYTES) throw new BadFrameException("audio frame shorter than its header");
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int stream = Byte.toUnsignedInt(header.get());
        int flags = Byte.toUnsignedInt(header.get());
        header.getShort();
        long seq = Integer.toUnsignedLong(header.getInt());
        long firstSample = header.getLong();
        long hostTimeNs = header.getLong();
        return new AudioChunk(stream, flags, seq, firstSample, hostTimeNs,
                Arrays.copyOfRange(payload, AUDIO_HEADER_BYTES, payload.length));
    }

    public byte[] encodeAudio(AudioChunk chunk) {
        ByteBuffer wire = ByteBuffer.allocate(AUDIO_HEADER_BYTES + chunk.pcm().length).order(ByteOrder.LITTLE_ENDIAN);
        wire.put((byte) chunk.stream()).put((byte) chunk.flags()).putShort((short) 0)
                .putInt((int) chunk.seq()).putLong(chunk.firstSample()).putLong(chunk.hostTimeNs())
                .put(chunk.pcm());
        return wire.array();
    }
}
