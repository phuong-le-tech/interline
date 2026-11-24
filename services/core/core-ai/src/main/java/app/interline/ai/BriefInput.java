package app.interline.ai;

import java.util.List;

public record BriefInput(String meetingId, String title, List<String> participants,
                         List<RelatedMeeting> related, List<OpenAction> openActions) {
    public record RelatedMeeting(String meetingId, String title, long date, List<String> notes) { }
    public record OpenAction(String id, String meetingId, String text, String ownerLabel, String dueDate) { }
}
