package app.interline.app;

import app.interline.agent.AgentPlatformCapabilities;
import app.interline.ai.ConnectionTester;
import app.interline.persistence.AiStore;
import app.interline.persistence.BriefStore;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.MeetingStore;
import app.interline.persistence.CaptureStore;
import app.interline.persistence.NoteStore;
import app.interline.persistence.ProviderStore;
import app.interline.persistence.SearchStore;
import app.interline.persistence.SettingsStore;
import app.interline.persistence.TemplateStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Wires the core: database ingress, recording use case, and the capture agent's lifecycle. */
@Configuration
@EnableConfigurationProperties(InterlineProperties.class)
class CoreConfiguration {
    private static final String DEV_VERSION = "dev";

    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean AgentSecrets agentSecrets() { return new AgentSecrets(); }

    @Bean CaptureStore captureStore(DurableCaptureIngest ingest) { return ingest.store(); }

    @Bean NoteStore noteStore(DurableCaptureIngest ingest) { return new NoteStore(ingest.database()); }

    @Bean AiStore aiStore(DurableCaptureIngest ingest) { return new AiStore(ingest.database()); }

    @Bean ProviderStore providerStore(DurableCaptureIngest ingest) { return new ProviderStore(ingest.database()); }

    @Bean TemplateStore templateStore(DurableCaptureIngest ingest) { return new TemplateStore(ingest.database()); }

    @Bean SearchStore searchStore(DurableCaptureIngest ingest) { return new SearchStore(ingest.database()); }

    @Bean CalendarStore calendarStore(DurableCaptureIngest ingest) { return new CalendarStore(ingest.database()); }

    @Bean BriefStore briefStore(DurableCaptureIngest ingest) { return new BriefStore(ingest.database()); }

    @Bean MeetingStore meetingStore(DurableCaptureIngest ingest) { return new MeetingStore(ingest.database()); }

    @Bean SettingsStore settingsStore(DurableCaptureIngest ingest) { return new SettingsStore(ingest.database()); }

    @Bean MeetingService meetingService(MeetingStore meetings, CalendarStore calendar, Clock clock) {
        return new MeetingService(meetings, calendar, clock);
    }

    @Bean BriefService briefService(MeetingService meetings, BriefStore cache, AiStore ai,
                                    ProviderService providers, Clock clock) {
        return new BriefService(meetings, cache, ai, providers, clock);
    }

    @Bean CalendarService calendarService(CalendarStore store, AgentSecrets secrets, Clock clock, InterlineProperties properties) {
        HttpIcsFetcher fetcher = new HttpIcsFetcher(properties.calendar().allowLoopbackHttp());
        ZoneId zone = properties.calendar().zone() == null || properties.calendar().zone().isBlank()
                ? ZoneId.systemDefault() : ZoneId.of(properties.calendar().zone());
        return new CalendarService(store, secrets, fetcher, new IcsCalendarSource(secrets, fetcher, zone), clock);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    UpcomingNotifier upcomingNotifier(CalendarStore store, Clock clock, EventBus events) {
        return new UpcomingNotifier(store, clock, payload -> events.publish("calendar.upcoming", payload));
    }

    @Bean ProviderService providerService(ProviderStore providers, AgentSecrets secrets, Clock clock) {
        return new ProviderService(providers, secrets, new ConnectionTester(), clock);
    }

    @Bean EnhancementService enhancementService(NoteStore notes, AiStore ai, TemplateStore templates,
                                               CaptureStore meetings, ProviderService providers, EventBus events, Clock clock) {
        return new EnhancementService(notes, ai, templates, meetings, providers, events, clock);
    }

    @Bean(destroyMethod = "close")
    DurableCaptureIngest ingest(InterlineProperties properties) {
        return new DurableCaptureIngest(properties.dataDir());
    }

    /** Repairs meetings left recording by a crash before anything can start a new one. */
    @Bean OrphanRecovery orphanRecovery(DurableCaptureIngest ingest, InterlineProperties properties) {
        OrphanRecovery recovery = new OrphanRecovery(ingest, properties.dataDir());
        recovery.run();
        return recovery;
    }

    @Bean RecordingService recordingService(DurableCaptureIngest ingest, EventBus events, Clock clock,
                                            InterlineProperties properties, OrphanRecovery recovery, ModelService models) {
        RecordingService service = new RecordingService(ingest, events, clock, properties.dataDir(), recovery,
                SpeechModels.overriddenBy(properties.agentArgs(), models), properties);
        ingest.useAecSource(() -> properties.echo().aec() != null ? properties.echo().aec() : service.agentCancelsEcho());
        return service;
    }

    @Bean(destroyMethod = "close")
    ModelService modelService(InterlineProperties properties, EventBus events) {
        return new ModelService(properties.models().catalog(), properties.dataDir(), events);
    }

    @Bean String coreVersion(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        return properties == null ? DEV_VERSION : properties.getVersion();
    }

    /** Starts the agent after the context is up and stops recording, then the agent, on the way down. */
    @Bean SmartLifecycle captureLifecycle(InterlineProperties properties, RecordingService recording, Clock clock,
                                          String coreVersion, ModelService models, AgentSecrets secrets, CalendarService calendar, EventBus events) {
        return new SmartLifecycle() {
            private AgentPlatformCapabilities capture;

            @Override public synchronized void start() {
                if (!properties.hasAgent()) return;
                List<String> command = new ArrayList<>();
                command.add(properties.agent());
                command.add("--protocol=1");
                command.add("--log-level=info");
                command.addAll(properties.agentArgs());
                if (command.stream().noneMatch(arg -> arg.startsWith("--model="))) command.addAll(models.agentArguments());
                capture = new AgentPlatformCapabilities(new AgentPlatformCapabilities.Config(command,
                        properties.logDir().resolve("agent.log"), properties.agentHeartbeatInterval(), coreVersion),
                        clock, recording);
                secrets.bind(capture.secrets());
                calendar.bind(capture, events);
                recording.bind(capture);
                capture.start();
            }

            @Override public synchronized void stop() {
                recording.shutdown();
                secrets.unbind();
                if (capture != null) capture.close();
                capture = null;
            }

            @Override public synchronized boolean isRunning() { return capture != null; }
        };
    }
}
