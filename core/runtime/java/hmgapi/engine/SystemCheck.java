package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * System requirements: which head units a mod (or one of its parts) is for.
 * Used by the patch engine and by the hook runtime's mod loader, so a mod is
 * refused the same way whether it patches files or only hooks.
 *
 * mod.json "system" (or a part's "system"):
 *
 *   "system": {
 *     "make":     ["kia", "hyundai"],          car make (from the model table, or the owner's setting)
 *     "model":    ["CVEV", "NEEV", "NEEV23"],  model code: first field of ro.build.product
 *     "name":     ["EV6"],                     model name (table / owner's setting)
 *     "year":     ">=2022 <2025",              model year: the owner's setting (the unit doesn't know it)
 *     "region":   ["USA", "CAN"],              second field of ro.build.product
 *     "firmware": [">=260206"],                last field of ro.build.product
 *     "product":  ["CVEV.USA."],               ro.build.product prefixes
 *     "platform": ["tcc803x"],                 ro.board.platform
 *     "android":  "4.4.2",                     ro.build.version.release
 *     "onebin":   ["cvev_us"],                 ro.mobis.onebindir
 *     "features": ["hmgapi.wifi", "!com.hkmc.hardware.ev"],   all must hold
 *     "props":    {"ro.product.opt.stepofspec": ">=100"}
 *   }
 *
 * "system": "all" (or no "system") is any head unit. Every option may be left out or set to "all" (or "*"): any value. Otherwise a
 * value or a list: the unit must match one entry; "!x" entries exclude. For
 * firmware, year, android and props, an entry starting with an operator
 * (>=, >, <=, <, =, ^, ~) is a version range. "features" lists what the unit
 * must have (or, with "!", must not); features are the <feature name> entries
 * in /system/etc/permissions plus HMG-API's own: hmgapi.wifi (a Wi-Fi chip
 * answered on SDIO; the wifi.module feature file is there even without one).
 *
 * "system" may also be a list of such objects: any one of them is enough, for
 * a mod that supports e.g. the US EV6 on 260507 and the Canadian Ioniq 5 on 260206.
 *
 * Unknown facts (no model year set, a model not in the table) fail a
 * requirement that lists values, and the reason says what to set.
 */
public final class SystemCheck {
    private SystemCheck() {}

    /** model code -> {make, name}. Owner's hmgapi.json "car" overrides it. */
    static final String[][] MODELS = {
        {"CVEV", "kia", "EV6"},
        {"NEEV", "hyundai", "Ioniq 5"},
        {"NEEV23", "hyundai", "Ioniq 5"},
        {"DL3", "kia", "K5"},
        {"DL3HEV", "kia", "K5 Hybrid"},
    };

    static final String[] KEYS = {"make", "model", "name", "year", "region", "firmware", "product",
        "platform", "android", "onebin", "features", "props"};

    /** a Wi-Fi chip answered on SDIO on this unit once (hmgapi.wifi) */
    static final String WIFI_SEEN = "/data/hmgapi/public/wifi-chip";

    /** This firmware can leave an existing chip unpowered until hotspot is restored. */
    public static boolean koreanIoniq5WifiUnconfirmed(Facts facts) {
        return "NEEV".equals(facts.get("model")) && "KOR".equals(facts.get("region"))
                && "260902".equals(facts.get("firmware")) && !facts.features().contains("hmgapi.wifi");
    }

    /** What the head unit is. */
    public static final class Facts {
        final Map<String, String> f = new LinkedHashMap<String, String>();
        final String root;
        final Map<String, String> fileProps;
        TreeSet<String> features;

        /**
         * root: "" on the unit, a folder in tests. Properties come from
         * android.os.SystemProperties, or root/props.txt (k=v lines) when that
         * isn't there (desktop tests). car: hmgapi.json's "car" object or null.
         */
        public Facts(String root, Map<String, Object> car) {
            this.root = root;
            fileProps = readProps(new File(root + "/props.txt"));
            String product = env("HMGAPI_PRODUCT", prop("ro.build.product"));
            f.put("product", product);
            if (product != null) {
                String[] p = product.split("\\.");
                f.put("model", p.length > 0 ? p[0] : null);
                f.put("region", p.length > 1 ? p[1] : null);
                f.put("firmware", p.length > 2 ? p[p.length - 1] : null);
            }
            for (String[] m : MODELS) {
                if (m[0].equals(f.get("model"))) {
                    f.put("make", m[1]);
                    f.put("name", m[2]);
                }
            }
            f.put("platform", prop("ro.board.platform"));
            f.put("android", prop("ro.build.version.release"));
            f.put("onebin", env("HMGAPI_ONEBIN", prop("ro.mobis.onebindir")));
            if (car != null) {
                for (String k : new String[] {"make", "name", "year"}) {
                    if (car.get(k) != null) f.put(k, String.valueOf(car.get(k)));
                }
            }
        }

        public String get(String k) {
            return f.get(k);
        }

        public String prop(String k) {
            if (fileProps != null) return fileProps.get(k);
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                String v = (String) sp.getMethod("get", String.class).invoke(null, k);
                return v == null || v.isEmpty() ? null : v;
            } catch (Throwable t) {
                return null;
            }
        }

        /** Read only when a requirement asks: every app start runs the mod loader. */
        public TreeSet<String> features() {
            if (features != null) return features;
            features = new TreeSet<String>();
            File[] xs = new File(root + "/system/etc/permissions").listFiles();
            if (xs != null) {
                for (File x : xs) {
                    if (!x.getName().endsWith(".xml")) continue;
                    try {
                        String t = Engine.read(x);
                        for (int i = t.indexOf("<feature"); i >= 0; i = t.indexOf("<feature", i + 1)) {
                            int n = t.indexOf("name=\"", i), e = n < 0 ? -1 : t.indexOf('"', n + 6);
                            if (n > 0 && e > 0 && n < t.indexOf('>', i)) features.add(t.substring(n + 6, e));
                        }
                    } catch (Exception ignored) {
                        // unreadable file: its features don't count
                    }
                }
            }
            // the chip shows up on SDIO a few seconds into boot, after the zygote loads mods;
            // once seen it's remembered (the engine, as root, writes the marker)
            String[] sdio = new File(root + "/sys/bus/sdio/devices").list();
            File seen = new File(root + WIFI_SEEN);
            if (sdio != null && sdio.length > 0) {
                features.add("hmgapi.wifi");
                if (!seen.exists()) {
                    try {
                        seen.createNewFile();
                    } catch (Exception ignored) {
                        // not root: the engine records it
                    }
                }
            } else if (seen.exists()) {
                features.add("hmgapi.wifi");
            }
            return features;
        }

        /** One line for logs and the app: what HMG-API thinks this unit is. */
        public String describe() {
            StringBuilder s = new StringBuilder();
            for (Map.Entry<String, String> e : f.entrySet()) {
                if (s.length() > 0) s.append(", ");
                s.append(e.getKey()).append(' ').append(e.getValue() == null ? "?" : e.getValue());
            }
            return s.toString();
        }

        static String env(String k, String d) {
            String v = System.getenv(k);
            return v == null ? d : v;
        }

        static Map<String, String> readProps(File f) {
            if (!f.isFile()) return null;
            Map<String, String> m = new LinkedHashMap<String, String>();
            try {
                for (String l : Engine.read(f).split("\n")) {
                    int eq = l.indexOf('=');
                    if (eq > 0) m.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
                }
            } catch (Exception ignored) {
                // no props
            }
            return m;
        }
    }

    /** null if the unit meets the requirements, else why not (plain words). */
    @SuppressWarnings("unchecked")
    public static String check(Object system, Facts facts) {
        if (system == null || (!(system instanceof Map) && !(system instanceof List) && isAll(system))) return null;
        if (system instanceof List) {
            List<Object> alts = (List<Object>) system;
            if (alts.isEmpty()) return null;
            List<String> why = new ArrayList<String>();
            for (Object o : alts) {
                String r = check(o, facts);
                if (r == null) return null;
                why.add(r);
            }
            return alts.size() == 1 ? why.get(0) : "matches none of its " + alts.size() + " supported setups (" + join(why, "; ") + ")";
        }
        if (!(system instanceof Map)) return "\"system\" must be an object or a list of objects";
        Map<String, Object> req = (Map<String, Object>) system;
        for (String k : req.keySet()) {
            boolean known = false;
            for (String x : KEYS) known |= x.equals(k);
            if (!known) return "unknown system requirement \"" + k + "\"";
        }
        for (String k : KEYS) {
            Object spec = req.get(k);
            if (spec == null || isAll(spec)) continue;
            String r;
            if ("features".equals(k)) r = features(spec, facts);
            else if ("props".equals(k)) r = props(spec, facts);
            else r = one(k, facts.get(k), spec);
            if (r != null) return r;
        }
        return null;
    }

    static boolean isAll(Object spec) {
        if (spec instanceof List) {
            for (Object o : (List<?>) spec) if (isAll(o)) return true;
            return false;
        }
        String s = String.valueOf(spec).trim();
        return "all".equalsIgnoreCase(s) || "*".equals(s);
    }

    static List<String> entries(Object spec) {
        List<String> out = new ArrayList<String>();
        if (spec instanceof List) for (Object o : (List<?>) spec) out.add(String.valueOf(o).trim());
        else out.add(String.valueOf(spec).trim());
        return out;
    }

    static final String[] HINT = {
        "year", "set the car's model year in HMG-API's settings (car.year)",
        "make", "set the car's make in HMG-API's settings (car.make)",
        "name", "set the car's model name in HMG-API's settings (car.name)",
    };

    /** One option: the fact must match a positive entry (if any) and no "!" entry. */
    static String one(String key, String have, Object spec) {
        List<String> pos = new ArrayList<String>(), neg = new ArrayList<String>();
        for (String e : entries(spec)) {
            if (e.startsWith("!")) neg.add(e.substring(1).trim());
            else pos.add(e);
        }
        if (have == null) {
            if (pos.isEmpty()) return null;
            String hint = "";
            for (int i = 0; i < HINT.length; i += 2) if (HINT[i].equals(key)) hint = ": " + HINT[i + 1];
            return "needs " + key + " " + join(pos, " or ") + ", and this unit's " + key + " is unknown" + hint;
        }
        for (String e : neg) {
            if (matches(key, have, e)) return "not for " + key + " " + have;
        }
        if (pos.isEmpty()) return null;
        for (String e : pos) if (matches(key, have, e)) return null;
        return "needs " + key + " " + join(pos, " or ") + " (this unit: " + have + ")";
    }

    static boolean matches(String key, String have, String e) {
        if ("product".equals(key)) return have.toUpperCase().startsWith(e.toUpperCase());
        if (isRange(e)) {
            try {
                return Version.satisfies(have, e);
            } catch (IllegalArgumentException x) {
                return false;   // not a version (a text prop): a range can't hold
            }
        }
        return have.equalsIgnoreCase(e);
    }

    static boolean isRange(String e) {
        return !e.isEmpty() && "<>=^~".indexOf(e.charAt(0)) >= 0;
    }

    static String features(Object spec, Facts facts) {
        for (String e : entries(spec)) {
            boolean not = e.startsWith("!");
            String name = not ? e.substring(1).trim() : e;
            boolean has = facts.features().contains(name);
            if (not && has) return "not for units with " + name;
            if (!not && !has) return "needs " + name + (name.equals("hmgapi.wifi") ? " (no Wi-Fi chip found on this unit)" : "");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String props(Object spec, Facts facts) {
        if (!(spec instanceof Map)) return "\"props\" must be an object: {\"<property>\": <value or list>}";
        for (Map.Entry<String, Object> e : ((Map<String, Object>) spec).entrySet()) {
            if (isAll(e.getValue())) continue;
            String r = one(e.getKey(), facts.prop(e.getKey()), e.getValue());
            if (r != null) return r;
        }
        return null;
    }

    static String join(List<String> l, String sep) {
        StringBuilder s = new StringBuilder();
        for (String x : l) {
            if (s.length() > 0) s.append(sep);
            s.append(x);
        }
        return s.toString();
    }

    /** hmgapi.json overrides.ignore_system: true, or a list of mod ids. At the owner's own risk. */
    @SuppressWarnings("unchecked")
    public static boolean ignored(Map<String, Object> hmgapi, String id) {
        Object o = hmgapi.get("overrides");
        if (!(o instanceof Map)) return false;
        Object v = ((Map<String, Object>) o).get("ignore_system");
        if (Boolean.TRUE.equals(v)) return true;
        return v instanceof List && ((List<Object>) v).contains(id);
    }

    /** Test/inspect: print the facts, or check a mod folder. */
    public static void main(String[] a) throws Exception {
        String root = Facts.env("HMGAPI_ROOT", "");
        File lj = new File(root + "/data/hmgapi/settings/hmgapi.json");
        Map<String, Object> hmgapi = lj.isFile() ? Json.object(Engine.read(lj)) : new LinkedHashMap<String, Object>();
        Facts facts = new Facts(root, (Map<String, Object>) hmgapi.get("car"));
        if (a.length == 0) {
            System.out.println(facts.describe());
            System.out.println("features: " + facts.features().size() + (facts.features().contains("hmgapi.wifi") ? ", Wi-Fi chip found" : ", no Wi-Fi chip"));
            return;
        }
        Map<String, Object> mod = Json.object(Engine.read(new File(a[0], "mod.json")));
        String r = check(mod.get("system"), facts);
        System.out.println(r == null ? "ok: " + mod.get("id") + " supports this unit" : mod.get("id") + ": " + r);
        System.exit(r == null ? 0 : 1);
    }
}
