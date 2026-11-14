package app.interline.agent;

import java.io.*;

/** Stream transport boundary. Malformed frames must emit bad_frame and close. */
public final class AgentConnection {
    public AgentConnection(InputStream input, OutputStream output) { }
    public Frame receive() throws IOException { return null; }
    /** Read agent hello, write the negotiated ack/reject, and return that response. */
    public ControlMessage handshake(int supportedMin, int supportedMax, String coreVersion) throws IOException { return null; }
}
