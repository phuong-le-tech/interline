package app.interline.agent;

import java.io.*;

/** Agent Protocol v1 codec signature scaffold. Limits include the kind byte. */
public final class FrameCodec {
    public Frame read(InputStream input) throws IOException { return null; }
    public void write(OutputStream output, Frame frame) throws IOException { }
    public ControlMessage decodeControl(byte[] payload) throws IOException { return null; }
    public byte[] encodeControl(ControlMessage message) throws IOException { return null; }
    public AudioChunk decodeAudio(byte[] payload) throws IOException { return null; }
    public byte[] encodeAudio(AudioChunk chunk) throws IOException { return null; }
}
