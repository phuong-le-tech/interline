package app.interline.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command line of the executable core, translated to the {@code interline.*} properties the context binds. */
final class CoreArguments {
    private static final Map<String, String> SINGLE = Map.of(
            "--data-dir", "interline.data-dir",
            "--log-dir", "interline.log-dir",
            "--agent", "interline.agent",
            "--parent", "interline.parent");

    private CoreArguments() { }

    static Map<String, Object> parse(String[] args) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> agentArgs = new ArrayList<>();
        List<String> origins = new ArrayList<>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (equals < 0) throw new IllegalArgumentException("expected --name=value, got " + arg);
            String name = arg.substring(0, equals);
            String value = arg.substring(equals + 1);
            if (name.equals("--agent-arg")) agentArgs.add(value);
            else if (name.equals("--allow-origin")) origins.add(value);
            else if (SINGLE.containsKey(name)) properties.put(SINGLE.get(name), value);
            else throw new IllegalArgumentException("unknown option " + name);
        }
        for (int i = 0; i < agentArgs.size(); i++) properties.put("interline.agent-args[" + i + "]", agentArgs.get(i));
        for (int i = 0; i < origins.size(); i++) properties.put("interline.allowed-origins[" + i + "]", origins.get(i));
        return properties;
    }
}
