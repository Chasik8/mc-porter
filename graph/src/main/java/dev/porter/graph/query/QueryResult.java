package dev.porter.graph.query;

import dev.porter.graph.model.Edge;
import dev.porter.graph.model.SymbolRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contract {@code queryResult}: path through the chain, worst status, minimum confidence, resulting symbols. */
public record QueryResult(SymbolRef symbol, String to, List<Edge> path, Edge.Status status, double confidence,
                          List<SymbolRef> results, Missing missing) {
    /** Set when the chain broke: version and reason. */
    public record Missing(String at, String reason) {
    }

    static QueryResult missing(SymbolRef s, String to, String at, String reason) {
        return new QueryResult(s, to, List.of(), Edge.Status.REMOVED, 0.0, List.of(), new Missing(at, reason));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("type", "queryResult");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("symbol", symbol.toJson());
        q.put("to", to);
        m.put("query", q);
        List<Object> p = new ArrayList<>();
        path.forEach(e -> p.add(e.toJson()));
        m.put("path", p);
        m.put("status", status.json);
        m.put("confidence", confidence);
        List<Object> r = new ArrayList<>();
        results.forEach(x -> r.add(x.toJson()));
        m.put("results", r);
        if (missing != null) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("at", missing.at());
            x.put("reason", missing.reason());
            m.put("missing", x);
        }
        return m;
    }
}
