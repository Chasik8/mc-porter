package ic2cprofile.extract;

import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.concepts.MenuSupport;
import dev.porter.extract.forge1192.interp.V.Obj;

/** Field access on interpreted IC2C objects (source names first, then official names of SRG fields). */
final class MenuFields {
    private MenuFields() {
    }

    static Object field(ExtractContext ctx, Obj o, String name) {
        if (o.fields.containsKey(name)) {
            return o.fields.get(name);
        }
        return MenuSupport.field(ctx, o, name);
    }
}
