package dev.porter.assets.lang;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading/writing of lang files in both formats.
 *
 * <ul>
 *   <li>JSON (1.13+): a flat object {@code key: text}, file {@code lang/<xx_yy>.json}.</li>
 *   <li>Properties-like {@code .lang} (≤ 1.12): one {@code key=value} per line, UTF-8, lines starting with {@code #}
 *       and empty lines ignored, split on the first {@code =} (verified against 1.7.10 {@code Locale#loadLocaleData}).
 *       Values cannot span lines: newlines are written as the two characters {@code \n} and the mod code must expand
 *       them (reported as a warning).</li>
 * </ul>
 */
public final class LangFiles {
    /** 1.13+ locale file name: {@code en_us.json}. */
    public static final Pattern JSON_LOCALE = Pattern.compile("^([a-z]{2,3}_[a-z]{2,3})\\.json$");
    /** Non-standard split file {@code <prefix>_<locale>.json} (loaded by some mods through a mixin). */
    public static final Pattern JSON_SPLIT = Pattern.compile("^(.+)_([a-z]{2,3}_[a-z]{2,3})\\.json$");

    /** Locales shipped with Minecraft 1.7.10 (from the 1.7.10 asset index). Target names outside it are warned about. */
    public static final Set<String> LOCALES_1_7_10 = Set.of(
            "af_ZA", "ar_SA", "ast_ES", "az_AZ", "bg_BG", "ca_ES", "cs_CZ", "cy_GB", "da_DK", "de_DE", "el_GR", "en_AU",
            "en_CA", "en_GB", "en_PT", "en_US", "eo_UY", "es_AR", "es_ES", "es_MX", "es_UY", "es_VE", "et_EE", "eu_ES",
            "fa_IR", "fi_FI", "fil_PH", "fr_CA", "fr_FR", "ga_IE", "gl_ES", "gv_IM", "he_IL", "hi_IN", "hr_HR", "hu_HU",
            "hy_AM", "id_ID", "is_IS", "it_IT", "ja_JP", "ka_GE", "ko_KR", "kw_GB", "la_LA", "lb_LU", "lt_LT", "lv_LV",
            "mi_NZ", "ms_MY", "mt_MT", "nds_DE", "nl_NL", "nn_NO", "no_NO", "oc_FR", "pl_PL", "pt_BR", "pt_PT", "qya_AA",
            "ro_RO", "ru_RU", "sk_SK", "sl_SI", "sr_SP", "sv_SE", "th_TH", "tlh_AA", "tr_TR", "uk_UA", "val_ES", "vi_VN",
            "zh_CN", "zh_TW");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LangFiles() {
    }

    /** Result of parsing a JSON lang file: entries in file order plus problems (duplicates, non-string values). */
    public record Parsed(Map<String, String> entries, List<String> duplicates, List<String> nonString) {
    }

    /** Parses a flat JSON lang object, keeping order and reporting duplicate keys (last wins, like vanilla). */
    public static Parsed parseJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> dups = new java.util.ArrayList<>();
        List<String> nonString = new java.util.ArrayList<>();
        try (JsonParser p = MAPPER.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("lang file is not a JSON object");
            }
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
                String key = p.currentName();
                JsonToken v = p.nextToken();
                String value;
                if (v == JsonToken.VALUE_STRING) {
                    value = p.getString();
                } else if (v.isScalarValue()) {
                    // Vanilla 1.19.2 Language uses GsonHelper.convertToString: numbers/booleans become text.
                    value = p.getString();
                    nonString.add(key);
                } else {
                    p.skipChildren();
                    nonString.add(key);
                    continue;
                }
                if (out.put(key, value) != null) {
                    dups.add(key);
                }
            }
            if (t != JsonToken.END_OBJECT) {
                throw new IllegalArgumentException("unexpected token " + t);
            }
        }
        return new Parsed(out, dups, nonString);
    }

    /** Writes a JSON lang file (two-space indent, LF, insertion order). */
    public static String writeJson(Map<String, String> entries) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries).replace("\r\n", "\n") + "\n";
    }

    /**
     * Writes a {@code .lang} file. Keys that cannot be represented (containing {@code =} or a line break, empty, or
     * starting with {@code #}) are passed to {@code unrepresentable} and skipped.
     */
    public static String writeLang(Map<String, String> entries, Consumer<String> unrepresentable) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String k = e.getKey();
            if (!representableKey(k)) {
                unrepresentable.accept(k);
                continue;
            }
            b.append(k).append('=').append(escapeValue(e.getValue())).append('\n');
        }
        return b.toString();
    }

    public static boolean representableKey(String k) {
        return !k.isEmpty() && k.charAt(0) != '#' && k.indexOf('=') < 0 && k.indexOf('\n') < 0 && k.indexOf('\r') < 0;
    }

    public static String escapeValue(String v) {
        return v.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    public static boolean multiline(String v) {
        return v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
    }

    /** Parses a {@code .lang} file the way 1.7.10 does; {@code \n} sequences are expanded only if asked. */
    public static Map<String, String> parseLang(String text, boolean expandNewlines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String v = line.substring(eq + 1);
            out.put(line.substring(0, eq), expandNewlines ? v.replace("\\n", "\n") : v);
        }
        return out;
    }

    /** {@code ru_ru} → {@code ru_RU} (≤ 1.10 file names: language lower case, region upper case). */
    public static String legacyLocale(String locale) {
        Matcher m = Pattern.compile("^([a-z]{2,3})_([a-z]{2,3})$").matcher(locale);
        if (!m.matches()) {
            return locale;
        }
        return m.group(1) + "_" + m.group(2).toUpperCase(java.util.Locale.ROOT);
    }
}
