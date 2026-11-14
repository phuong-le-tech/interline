package app.interline.domain;

/** Port: everything the core needs from the host OS, reached through an agent. */
public interface PlatformCapabilities {
    AudioCapture audioCapture();
}
