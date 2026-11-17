package app.interline.ai;

import java.util.List;

public record FollowUpInput(String meetingId, String title, String language, List<EnhanceInput.UserBlock> blocks,
                            List<EnhanceInput.SegmentLine> segments) { }
