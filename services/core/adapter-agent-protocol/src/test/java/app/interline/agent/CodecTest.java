package app.interline.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class CodecTest {
    private final FrameCodec codec = new FrameCodec();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Property(tries = 150, seed = "210001")
    void AC2_controlRoundTrip(@ForAll("texts") String text) throws Exception {
        var body = JSON.createObjectNode().put("text", text);
        var message = new ControlMessage(1, "stt.final", null, null, 123L, body);
        byte[] payload = codec.encodeControl(message);
        assertThat(payload).isNotNull();
        var out = new ByteArrayOutputStream();
        codec.write(out, new Frame(1, payload));
        assertThat(ByteBuffer.wrap(out.toByteArray()).getInt()).isEqualTo(payload.length + 1);
        Frame frame = codec.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(frame).isNotNull();
        assertThat(frame.kind()).isEqualTo(1);
        assertThat(codec.decodeControl(frame.payload())).isEqualTo(message);
    }

    @Provide Arbitrary<String> texts() {
        return Arbitraries.strings().withChars('a', 'z', '\u00e9', '\u4e2d', '\n', '"', '\\').ofMaxLength(2048);
    }

    @Property(tries = 150, seed = "210002")
    void AC2_audioRoundTrip(@ForAll("pcm") byte[] pcm, @ForAll boolean system,
                        @ForAll @net.jqwik.api.constraints.IntRange(min=0,max=7) int flags,
                        @ForAll @net.jqwik.api.constraints.LongRange(min=0,max=4294967295L) long seq,
                        @ForAll @net.jqwik.api.constraints.LongRange(min=0,max=Long.MAX_VALUE) long sample) throws Exception {
        var chunk = new AudioChunk(system ? 1 : 0, flags, seq, sample, 987654321L, pcm);
        byte[] payload = codec.encodeAudio(chunk);
        assertThat(payload).isNotNull().hasSize(24 + pcm.length);
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Byte.toUnsignedInt(header.get())).isEqualTo(chunk.stream());
        assertThat(Byte.toUnsignedInt(header.get())).isEqualTo(flags);
        assertThat(header.getShort()).isZero();
        assertThat(Integer.toUnsignedLong(header.getInt())).isEqualTo(seq);
        assertThat(header.getLong()).isEqualTo(sample);
        assertThat(header.getLong()).isEqualTo(987654321L);
        var out = new ByteArrayOutputStream();
        codec.write(out, new Frame(2, payload));
        assertThat(ByteBuffer.wrap(out.toByteArray()).getInt()).isEqualTo(25 + pcm.length);
        Frame frame = codec.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(frame).isNotNull();
        AudioChunk decoded = codec.decodeAudio(frame.payload());
        assertThat(decoded).isNotNull();
        assertThat(decoded.stream()).isEqualTo(chunk.stream());
        assertThat(decoded.flags()).isEqualTo(flags);
        assertThat(decoded.seq()).isEqualTo(seq);
        assertThat(decoded.firstSample()).isEqualTo(sample);
        assertThat(decoded.hostTimeNs()).isEqualTo(987654321L);
        assertThat(decoded.pcm()).containsExactly(pcm);
    }

    @Provide Arbitrary<byte[]> pcm() {
        return Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(1600)
                .map(bytes -> Arrays.copyOf(bytes, bytes.length * 2));
    }

    @Test void AC2_unknownEnvelopeAndBodyFieldsAreIgnored() throws Exception {
        var expected = codec.decodeControl("{\"v\":1,\"t\":\"heartbeat\",\"ts\":0,\"body\":{\"rss_mb\":12}}".getBytes(StandardCharsets.UTF_8));
        var actual = codec.decodeControl("{\"v\":1,\"t\":\"heartbeat\",\"ts\":0,\"future\":{\"nested\":[1,2]},\"body\":{\"rss_mb\":12,\"new_metric\":true}}".getBytes(StandardCharsets.UTF_8));
        assertThat(actual).isNotNull();
        assertThat(actual.type()).isEqualTo("heartbeat");
        assertThat(actual.version()).isEqualTo(1);
        assertThat(actual.body().path("rss_mb").asInt()).isEqualTo(12);
        assertThat(expected).isNotNull();
        assertThat(actual.type()).isEqualTo(expected.type());
    }

    @ParameterizedTest @ValueSource(ints={1,2})
    void AC2_maxMinusOneAndMaxAreAcceptedIncludingKind(int kind) throws Exception {
        int max = kind == 1 ? 1048576 : 65536;
        for (int length : new int[]{max - 1, max}) {
            byte[] payload;
            if (kind == 1) {
                String prefix = "{\"v\":1,\"t\":\"heartbeat\",\"ts\":0,\"body\":{\"padding\":\"";
                String suffix = "\"}}";
                payload = (prefix + "x".repeat(length - 1 - prefix.length() - suffix.length()) + suffix).getBytes(StandardCharsets.UTF_8);
            } else payload = new byte[length - 1];
            var frame = codec.read(new ByteArrayInputStream(wire(kind, payload)));
            assertThat(frame).as("kind %s length %s", kind, length).isNotNull();
            assertThat(frame.kind()).isEqualTo(kind);
            assertThat(frame.payload()).containsExactly(payload);
        }
    }

    @Test void AC2_emptyStreamIsCleanEof() throws Exception {
        assertThat(codec.read(new ByteArrayInputStream(new byte[0]))).isNull();
    }

    @Test void AC2_splitReadsAndConsecutiveFramesAreReassembled() throws Exception {
        byte[] payload = "{\"v\":1,\"t\":\"heartbeat\",\"ts\":0,\"body\":{}}".getBytes(StandardCharsets.UTF_8);
        var bytes = new ByteArrayOutputStream();
        bytes.write(wire(1, payload)); bytes.write(wire(1, payload));
        var split = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1, len)); }
        };
        for (int i=0;i<2;i++) {
            Frame frame = codec.read(split);
            assertThat(frame).isNotNull();
            assertThat(frame.payload()).containsExactly(payload);
        }
        assertThat(codec.read(split)).isNull();
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9})
    void AC2_malformedWireEmitsBadFrameThenClosesRealTransport(int scenario) throws Exception {
        byte[] input = switch (scenario) {
            case 0 -> ByteBuffer.allocate(5).putInt(1048577).put((byte)1).array();
            case 1 -> ByteBuffer.allocate(5).putInt(65537).put((byte)2).array();
            case 2 -> wire(127, new byte[0]);
            case 3 -> new byte[]{0,0,0,0};
            case 4 -> new byte[]{0,0};
            case 5 -> ByteBuffer.allocate(7).putInt(20).put((byte)1).putShort((short)0).array();
            case 6 -> ByteBuffer.allocate(5).putInt(-1).put((byte)1).array();
            case 7 -> wire(1, new byte[0]);
            case 8 -> wire(2, new byte[23]);
            default -> ByteBuffer.allocate(4).putInt(1).array();
        };
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket sender = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket receiver = listener.accept();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            sender.setSoTimeout(2000);
            Future<?> serving = executor.submit(() -> {
                try (receiver) {
                    new AgentConnection(receiver.getInputStream(), receiver.getOutputStream()).receive();
                    assertThat(receiver.isClosed()).as("The receiver, not the test cleanup, closes malformed transport").isTrue();
                }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
            sender.getOutputStream().write(input);
            sender.getOutputStream().flush();
            sender.shutdownOutput();
            // Independent parser: a broken production codec cannot certify its own error response.
            DataInputStream reply = new DataInputStream(sender.getInputStream());
            byte[] prefix = reply.readNBytes(4);
            assertThat(prefix).as("bad_frame must be written before EOF").hasSize(4);
            int length = ByteBuffer.wrap(prefix).getInt();
            assertThat(length).isBetween(2, 1048576);
            assertThat(reply.readUnsignedByte()).isEqualTo(1);
            byte[] payload = reply.readNBytes(length - 1);
            assertThat(payload).hasSize(length - 1);
            var error = JSON.readTree(payload);
            assertThat(error.path("t").asText()).isEqualTo("error");
            assertThat(error.path("body").path("code").asText()).isEqualTo("bad_frame");
            assertThat(reply.read()).isEqualTo(-1);
            serving.get(2, TimeUnit.SECONDS);
        }
    }

    static byte[] wire(int kind, byte[] payload) {
        return ByteBuffer.allocate(5 + payload.length).putInt(1 + payload.length).put((byte)kind).put(payload).array();
    }
}
