package dev.porter.port.config;

import dev.porter.core.json.Json;
import dev.porter.port.Text;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * A per-port configuration ({@code contracts/port-config.schema.json}): what to port (closure seeds and cuts), with
 * which rules, into which project, and how to compile/build/test it. Every string may use {@code ${NAME}}
 * variables from {@code vars:} or the environment. Relative paths resolve against the config file's directory,
 * except the few documented as project-relative ({@code sources}, {@code classpath.file}, {@code build.jar}).
 */
public final class PortConfig {
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)(?::-([^}]*))?}");

    private final Path file;
    private final Path dir;
    private final JsonNode root;

    private PortConfig(Path file, JsonNode root) {
        this.file = file.toAbsolutePath().normalize();
        this.dir = this.file.getParent();
        this.root = root;
    }

    public static PortConfig load(Path file) {
        return load(file, Map.of());
    }

    /** The config file as written, without variable substitution (for sections that carry no variables). */
    public static JsonNode raw(Path file) {
        String text = Text.read(file);
        return file.toString().endsWith(".json") ? Json.json().readTree(text) : Json.yaml().readTree(text);
    }

    /**
     * Loads a config with extra variables (defined by {@code porter run}: {@code RUN_DIR}, {@code INPUT_<NAME>}...);
     * they rank after {@code vars:} and before the environment.
     */
    public static PortConfig load(Path file, Map<String, String> extra) {
        JsonNode raw = raw(file);
        Map<String, String> vars = new LinkedHashMap<>(extra);
        // built-in: the directory of the config file (a profile refers to its own files through it)
        vars.put("CONFIG_DIR", file.toAbsolutePath().normalize().getParent().toString().replace('\\', '/'));
        JsonNode v = raw.path("vars");
        for (Map.Entry<String, JsonNode> e : v.properties()) {
            vars.put(e.getKey(), e.getValue().asString());
        }
        // vars may reference earlier vars / the environment; inside its own value a var means the environment
        // variable of that name (NAME: ${NAME:-default})
        Map<String, String> done = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            done.put(e.getKey(), subst(e.getValue(), done));
        }
        vars = done;
        return new PortConfig(file, substTree(raw, vars));
    }

    static String subst(String s, Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int at = s.indexOf("${", i);
            if (at < 0) {
                sb.append(s, i, s.length());
                break;
            }
            sb.append(s, i, at);
            // the matching brace (defaults may contain ${...} themselves)
            int depth = 0;
            int end = -1;
            for (int j = at; j < s.length(); j++) {
                if (s.startsWith("${", j)) {
                    depth++;
                    j++;
                } else if (s.charAt(j) == '}' && --depth == 0) {
                    end = j;
                    break;
                }
            }
            if (end < 0) {
                throw new IllegalArgumentException("unclosed ${ in '" + s + "'");
            }
            String body = s.substring(at + 2, end);
            int dflt = body.indexOf(":-");
            String k = dflt < 0 ? body : body.substring(0, dflt);
            if (!VAR.matcher("${" + k + "}").matches()) {
                throw new IllegalArgumentException("bad variable name ${" + body + "}");
            }
            String val = vars.containsKey(k) ? vars.get(k) : System.getenv(k);
            if ((val == null || val.isEmpty()) && dflt >= 0) {
                val = subst(body.substring(dflt + 2), vars); // ${NAME:-default}
            }
            if (val == null) {
                throw new IllegalArgumentException("undefined variable ${" + k + "} (define it under vars: or in the environment)");
            }
            sb.append(val);
            i = end + 1;
        }
        return sb.toString();
    }

    private static JsonNode substTree(JsonNode n, Map<String, String> vars) {
        if (n.isString()) {
            return StringNode.valueOf(subst(n.asString(), vars));
        }
        if (n.isArray()) {
            ArrayNode a = Json.json().createArrayNode();
            n.forEach(x -> a.add(substTree(x, vars)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = Json.json().createObjectNode();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                o.set(e.getKey(), e.getKey().equals("vars") ? e.getValue() : substTree(e.getValue(), vars));
            }
            return o;
        }
        return n;
    }

    public Path file() {
        return file;
    }

    /** Directory of the config file: base of relative paths and working directory of child steps. */
    public Path dir() {
        return dir;
    }

    public JsonNode root() {
        return root;
    }

    public String name() {
        return root.path("name").asString("port");
    }

    public String from() {
        return root.path("from").asString("forge-1.19.2");
    }

    public String to() {
        return root.path("to").asString("forge-1.7.10");
    }

    /** Bare Minecraft version of {@link #from()} ({@code 1.19.2}). */
    public String fromBare() {
        String f = from();
        return f.contains("-") ? f.substring(f.indexOf('-') + 1) : f;
    }

    public Path project() {
        return path(root.path("project").asString());
    }

    /** Java source root of the project. */
    public Path sources() {
        return project().resolve(root.path("sources").asString("src/main/java")).normalize();
    }

    public Path work() {
        JsonNode w = root.path("work");
        return w.isMissingNode() ? project().resolve("build/porter-port") : path(w.asString());
    }

    public boolean has(String section) {
        return root.has(section) && !root.get(section).isNull();
    }

    public JsonNode section(String name) {
        return root.path(name);
    }

    /** Command prefix for heavy steps (machine queue), may be empty. */
    public List<String> queue() {
        return strings(root.path("queue"));
    }

    public List<String> jvmArgs() {
        return strings(root.path("jvmArgs"));
    }

    public Path path(String p) {
        return dir.resolve(p).normalize();
    }

    public List<Path> paths(JsonNode arr) {
        List<Path> out = new ArrayList<>();
        for (String s : strings(arr)) {
            out.add(path(s));
        }
        return out;
    }

    public static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || arr.isMissingNode() || arr.isNull()) {
            return out;
        }
        if (arr.isArray()) {
            arr.forEach(x -> out.add(x.asString()));
        } else {
            out.add(arr.asString());
        }
        return out;
    }

    /**
     * Target compile classpath: {@code classpath.file} (project-relative, entries separated by ';' or newlines;
     * entries containing an {@code exclude} substring dropped) followed by {@code classpath.entries} (globs allowed).
     */
    public List<Path> classpath() {
        JsonNode cp = root.path("classpath");
        List<Path> out = new ArrayList<>();
        if (cp.has("file")) {
            Path f = project().resolve(cp.get("file").asString());
            List<String> excl = strings(cp.path("exclude"));
            for (String e : Text.read(f).split("[;\n]")) {
                String t = e.strip();
                if (!t.isEmpty() && excl.stream().noneMatch(t::contains)) {
                    out.add(Path.of(t));
                }
            }
        }
        for (String e : strings(cp.path("entries"))) {
            out.addAll(glob(e));
        }
        return out;
    }

    /** Jars named by a list of paths/globs (resolved against the config dir). */
    public List<Path> globs(JsonNode arr) {
        List<Path> out = new ArrayList<>();
        for (String s : strings(arr)) {
            out.addAll(glob(s));
        }
        return out;
    }

    /** A path, or all files matching a glob ({@code *}, {@code **}, {@code ?}) under its fixed prefix, sorted. */
    public List<Path> glob(String pattern) {
        String norm = pattern.replace('\\', '/');
        int star = indexOfGlob(norm);
        if (star < 0) {
            return List.of(path(pattern));
        }
        int slash = norm.lastIndexOf('/', star);
        Path base = path(slash < 0 ? "." : norm.substring(0, slash));
        String rest = norm.substring(slash + 1);
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + rest);
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(Files::isRegularFile).filter(p -> pm.matches(base.relativize(p))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("glob " + pattern + ": " + e.getMessage(), e);
        }
    }

    private static int indexOfGlob(String s) {
        int a = s.indexOf('*');
        int b = s.indexOf('?');
        return a < 0 ? b : (b < 0 ? a : Math.min(a, b));
    }
}
