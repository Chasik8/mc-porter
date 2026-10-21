import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds an MCP -> SRG member mapping (TSRG v1) for Minecraft 1.7.10 from Forge's packaged.srg (notch -> srg) and
 * MCP stable_12 fields.csv / methods.csv (srg -> mcp). Class names are identical in MCP and SRG for 1.7.10.
 *
 * Usage: java MakeMcpToSrg.java packaged.srg fields.csv methods.csv out.tsrg
 */
public class MakeMcpToSrg {
    public static void main(String[] args) throws IOException {
        Map<String, String> fieldNames = csv(Path.of(args[1]));
        Map<String, String> methodNames = csv(Path.of(args[2]));
        Map<String, StringBuilder> byClass = new TreeMap<>();
        int nf = 0;
        int nm = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] p = line.split(" ");
            switch (p[0]) {
                case "CL:" -> byClass.computeIfAbsent(p[2], k -> new StringBuilder());
                case "FD:" -> {
                    String srg = p[2];
                    String owner = srg.substring(0, srg.lastIndexOf('/'));
                    String name = srg.substring(srg.lastIndexOf('/') + 1);
                    String mcp = fieldNames.get(name);
                    if (mcp != null && !mcp.equals(name)) {
                        byClass.computeIfAbsent(owner, k -> new StringBuilder()).append('\t').append(mcp).append(' ').append(name).append('\n');
                        nf++;
                    }
                }
                case "MD:" -> {
                    String srg = p[3];
                    String owner = srg.substring(0, srg.lastIndexOf('/'));
                    String name = srg.substring(srg.lastIndexOf('/') + 1);
                    String mcp = methodNames.get(name);
                    if (mcp != null && !mcp.equals(name)) {
                        byClass.computeIfAbsent(owner, k -> new StringBuilder()).append('\t').append(mcp).append(' ')
                                .append(p[4]).append(' ').append(name).append('\n');
                        nm++;
                    }
                }
                default -> {
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, StringBuilder> e : byClass.entrySet()) {
            out.append(e.getKey()).append(' ').append(e.getKey()).append('\n').append(e.getValue());
        }
        Files.writeString(Path.of(args[3]), out.toString(), StandardCharsets.UTF_8);
        System.out.println("classes " + byClass.size() + ", fields " + nf + ", methods " + nm);
    }

    static Map<String, String> csv(Path file) throws IOException {
        Map<String, String> m = new HashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",", 3);
            if (p.length >= 2) {
                m.put(p[0], p[1]);
            }
        }
        return m;
    }
}
