package dev.porter.extract.forge1192;

import dev.porter.extract.forge1192.interp.Facts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Writes the fixture IR and a facts dump to build/fixture-ir (for inspection; always passes). */
class DumpTest {
    @Test
    void dump() throws IOException {
        ExtractContext ctx = Fixtures.example();
        Path out = Path.of("build", "fixture-ir");
        Extractor.write(ctx, out);
        List<String> lines = new ArrayList<>();
        for (Facts.Registration r : ctx.facts.registrations) {
            lines.add("REG " + r.registry() + " " + r.id() + " " + r.idUnknown() + " = " + r.value()
                    + (r.value() instanceof dev.porter.extract.forge1192.interp.V.VObj vo ? " args " + vo.args : "")
                    + " id(arg0)=" + (r.value() instanceof dev.porter.extract.forge1192.interp.V.VObj vo2
                    && !vo2.args.isEmpty() ? ctx.idOf(vo2.args.get(0)) : "-"));
        }
        for (Facts.Message m : ctx.facts.messages) {
            lines.add("MSG " + m.messageClass() + " " + m.index() + " " + m.direction());
        }
        for (var e : ctx.facts.events) {
            lines.add("EVT " + e.kind() + " " + e.name() + " " + e.root());
        }
        lines.addAll(ctx.registrar.log());
        for (var r : ctx.registrar.roots()) {
            lines.add("ROOT " + r.getClass().getSimpleName() + " " + r.getPosition());
        }
        Files.write(out.resolve("facts.txt"), lines);
    }
}
