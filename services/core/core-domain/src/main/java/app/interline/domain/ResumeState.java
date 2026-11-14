package app.interline.domain;

import java.util.List;
import java.util.Map;

/** What a restarted agent needs to continue a meeting without a sequence hole. */
public record ResumeState(List<String> previousSpoolDirs,
                          Map<StreamKind, Long> acked,
                          Map<StreamKind, Long> sttDoneUntilSample,
                          Map<StreamKind, Long> nextSeq) { }
