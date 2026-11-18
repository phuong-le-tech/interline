package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3BriefSupport.*;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2 review round 2 over the real core: the brief keeps deep history, concurrent PUTs answer with their own list. */
@Timeout(120)
class M3Round2MeetingsApiTest {
    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() {
        context = boot(data);
        port = port(context);
    }

    @AfterEach void stop() {
        if (context.isActive()) context.close();
    }

    @Test void aRelatedMeetingOlderThan20500NewerUnrelatedOnesStillAppearsInTheBrief() {
        insertMeeting(data, "old-related", "Quarterly roadmap sync", NOW - 400 * DAY);
        long newer = NOW - 2 * DAY;
        sql(data, "WITH RECURSIVE c(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM c WHERE i < 20500) "
                + "INSERT INTO meetings(id,title,status,created_at,updated_at) "
                + "SELECT 'u'||i, 'Unrelated '||i, 'done', ?, ? FROM c", newer, newer);
        String subject = createMeetingOk(port, "Quarterly roadmap sync", null);

        JsonNode brief = briefOk(port, subject);

        assertThat(texts(brief.path("related"), "meetingId")).contains("old-related");
        JsonNode match = null;
        for (JsonNode related : brief.path("related")) {
            if ("old-related".equals(related.path("meetingId").asText())) match = related;
        }
        assertThat(match).isNotNull();
        assertThat(match.path("matchedBy").asText()).isEqualTo("title");
    }

    @Test void concurrentPutsOnTheSameMeetingEachAnswerWithTheirOwnList() throws Exception {
        String id = createMeetingOk(port, "Shared meeting", null);
        int threads = 12;
        int rounds = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<Resp>> futures = new ArrayList<>();
                List<Set<String>> expected = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    String a = "r" + round + "t" + t + "a@example.com";
                    String b = "r" + round + "t" + t + "b@example.com";
                    expected.add(Set.of(a, b));
                    List<Object> body = List.of(person("A " + round + "-" + t, a), person("B " + round + "-" + t, b));
                    futures.add(pool.submit(() -> {
                        start.await(30, TimeUnit.SECONDS);
                        return putParticipants(port, id, body);
                    }));
                }
                for (int t = 0; t < threads; t++) {
                    Resp response = futures.get(t).get(60, TimeUnit.SECONDS);
                    Set<String> got = new HashSet<>(texts(ok(response, "put participants").json(), "email"));
                    assertThat(got).as("round %d thread %d", round, t).isEqualTo(expected.get(t));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
