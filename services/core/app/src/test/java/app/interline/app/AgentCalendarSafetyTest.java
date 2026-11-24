package app.interline.app;

import app.interline.agent.AgentPlatformCapabilities;
import app.interline.agent.CalendarSourceFixture;
import app.interline.agent.ControlMessage;
import app.interline.domain.CalendarEvent;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCalendarSafetyTest {
    @TempDir Path directory;

    @Test void missingCompletenessCannotDeleteAnExistingSyncWindow() {
        try (var db = SqliteDatabase.open(directory.resolve("db.sqlite"))) {
            var store = new CalendarStore(db);
            store.replaceAgentWindow(List.of(new CalendarEvent("keep", "Keep me", NOW + HOUR, NOW + 2 * HOUR,
                    null, List.of())), WINDOW_START, WINDOW_END, NOW, true);
            var body = ControlMessage.nodes().objectNode();
            body.putArray("events");
            var agent = mock(AgentPlatformCapabilities.class);
            when(agent.calendarSupported()).thenReturn(true);
            when(agent.calendar()).thenReturn(CalendarSourceFixture.reply(body));
            try (var service = new CalendarService(store, new CalendarServiceIncompleteTest.MemorySecrets(),
                    new HttpIcsFetcher(false), (from, to) -> { throw new AssertionError("ICS must not run"); },
                    Clock.fixed(NOW_INSTANT, ZoneOffset.UTC))) {
                service.bind(agent, new EventBus());
                assertThatThrownBy(service::sync).isInstanceOf(org.springframework.web.ErrorResponseException.class);
                assertThat(store.listEvents(WINDOW_START, WINDOW_END)).singleElement()
                        .satisfies(event -> assertThat(event.externalId()).isEqualTo("keep"));
            }
        }
    }
}
