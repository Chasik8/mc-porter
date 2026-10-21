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
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private final Path file;
    private final Path dir;
    private final JsonNode root;

    private PortConfig(Path file, JsonNode root) {
        this.file = file.toAbsolutePath().normalize();
        this.dir = this.file.getParent();
        this.root = root;
    }

    public static PortConfig load(Path file) {
        String text = Text.read(file);
        JsonNode raw = file.toString().endsWith(".json") ? Json.json().readTree(text) : Json.yaml().readTree(text);
        Map<String, String> vars = new LinkedHashMap<>();
        JsonNode v = raw.path("vars");
        for (Map.Entry<String, JsonNode> e : v.properties()) {
            vars.put(e.getKey(), e.getValue().asString());
        }
        // vars may reference earlier vars / the environment
        for (Map.Entry<String, String> e : vars.entrySet()) {
            e.setValue(subst(e.getValue(), vars));
        }
        return new PortConfig(file, substTree(raw, vars));
    }

    static String subst(String s, Map<String, String> vars) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String k = m.group(1);
            String val = vars.containsKey(k) ? vars.get(k) : System.getenv(k);
            if (val == null) {
                throw new IllegalArgumentException("undefined variable ${" + k + "} (define it under vars: or in the environment)");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
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
