package dev.porter.assets.steps;

import dev.porter.assets.AssetTree;
import dev.porter.assets.AssetVersion;
import dev.porter.assets.Context;
import dev.porter.assets.Step;
import dev.porter.assets.lang.LangFiles;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.12.2 (pack_format 3) → 1.10.2 (pack_format 2): lang file names go back to {@code xx_YY.lang}
 * ({@code ru_ru.lang} → {@code ru_RU.lang}); 1.11 lowercased them.
 */
public final class LangCaseStep implements Step {
    private static final Pattern LOWER = Pattern.compile("^([a-z]{2,3}_[a-z]{2,3})\\.lang$");

    @Override
    public AssetVersion from() {
        return AssetVersion.V1_12_2;
    }

    @Override
    public AssetVersion to() {
        return AssetVersion.V1_10_2;
    }

    @Override
    public String description() {
        return "lang file names xx_yy.lang -> xx_YY.lang";
    }

    @Override
    public void apply(AssetTree tree, Context ctx) {
        int renamed = 0;
        for (String p : tree.paths()) {
            String local = AssetTree.local(p);
            if (!local.startsWith("lang/") || local.indexOf('/', 5) >= 0) {
                continue;
            }
            Matcher m = LOWER.matcher(local.substring(5));
            if (!m.matches()) {
                continue;
            }
            String legacy = LangFiles.legacyLocale(m.group(1));
            if (!LangFiles.LOCALES_1_7_10.contains(legacy)) {
                ctx.warn("lang-unknown-locale", p, "locale " + legacy + " is not one of the 1.7.10 languages; file kept but never loaded");
            }
            tree.move(p, AssetTree.namespace(p) + "/lang/" + legacy + ".lang");
            renamed++;
        }
        ctx.metric("lang.filesRenamedToLegacyLocale", renamed);
    }
}
