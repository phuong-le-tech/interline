package app.interline.mockagent;

/** One scripted speech region: when it starts and ends on the session timeline, and what is said. */
record Region(String stream, String seg, long startSample, long endSample, String text) { }
