package app.interline.domain;

import java.util.List;
import java.util.Map;

/** What the validator knows: the meeting, the user's blocks (id to text, the fragment an addition sits under) and all segments. */
public record ValidationContext(String meetingId, Map<String, String> userBlockTexts, List<SegmentRef> segments) { }
