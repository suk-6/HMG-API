package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * HMG-API's patch engine: changes files (and Android settings) for the enabled
 * mods, always rebuilt from the stock originals, as one transaction.
 *
 *   engine plan                what would change, conflicts, restarts (writes nothing)
 *   engine apply [--trial]     build and install; --trial: undone at the next start unless kept
 *   engine keep                keep a trial apply
 *   engine restore             every file and setting HMG-API changed back to stock
 *   engine status              files HMG-API owns: stock, mods, expected and live md5
 *   engine recover             at start (hmgapid): roll back an unfinished or trial apply
 *
 * Mods: /data/hmgapi/mods/<id>/patches.json (see the wiki's patch engine page).
 * The goal: mods work side by side and never overwrite each other. HMG-API
 * provides hooks and extension points so a mod rarely needs to change a file;
 * where one does, the engine merges changes to the same file (keys, lines,
 * non-overlapping recipes) or refuses with the reason, and a mod that replaces
 * a whole file (delta, app, add over a stock file) owns it, so no other mod can
 * change it: the plan says so. A missing hook can be added to HMG-API (PR).
 *
 * Change types: add, props, lines, recipe, delta, app, setting, prop, bind.
 * bind: {"src": "/system/bin/x", "target": "/sbin/x", "service": "x"}: hmgapid
 * bind-mounts src over target and restarts the init service, checks it runs
 * src, else puts stock back (nothing on disk changes; a restart clears it).
 *
 * Safety (the unit must always boot): /system/build.prop only takes "props"
 * changes, never to ro.* keys, and every edit must keep every other line;
 * boot-critical files (see CRITICAL) are refused unless hmgapi.json
 * overrides.allow_critical lists the path; files HMG-API's installer owns are
 * always refused. Live properties ("prop") are persist.* only: they live in
 * /data/property, and removing that file undoes them. A prop's "value" "" means
 * unset. "mirror": a live (non-persist) property set to the same value, for init
 * triggers such as sys.usb.config; "restart": services restarted after the
 * change (ctl.stop + ctl.start). Both run after the result is printed, since
 * they may cut the adb link that asked (hmgapid lets the engine finish).
 *
 * Plain Java 7 and java.io only (Android 4.4 has no java.nio.file), so it runs
 * on a desktop JVM for tests: HMGAPI_ROOT puts / under a folder, HMGAPI_HOST=1
 * skips what only the head unit can do (remount, chmod/chown, pm, dexopt,
 * settings), HMGAPI_PRODUCT / HMGAPI_ONEBIN replace getprop, HMGAPI_LPATCH
 * names the lpatch binary.
 */
public final class Engine {
    public static final String HMGAPI_VERSION = "1.0.0-beta.5";
    /**
     * What a mod's "hmgapi" requirement is checked against: the release a beta leads to
     * (1.0.0-beta.1 meets ">=1.0.0", so mods made for 1.0.0 run on its betas). Updates
     * compare the full version: 1.0.0 is newer than 1.0.0-beta.1.
     */
    public static final String HMGAPI_API = HMGAPI_VERSION.replaceFirst("-.*$", "");
    final String root = env("HMGAPI_ROOT", "");
    final boolean host = "1".equals(env("HMGAPI_HOST", ""));
    final String lpatch = env("HMGAPI_LPATCH", "/system/bin/lpatch");
    /** How the enabled mods' hooks fit together (the same rules the zygote's loader uses). */
    HookPlan hookPlan() {
        Map<String, Map<String, Object>> enabled = new LinkedHashMap<String, Map<String, Object>>();
        File[] ds = new File(data, "mods").listFiles();
        if (ds != null) {
            java.util.Arrays.sort(ds);
            for (File d : ds) {
                File mj = new File(d, "mod.json");
                if (!d.isDirectory() || new File(d, ".disabled").exists() || !mj.isFile()) continue;
                try {
                    enabled.put(d.getName(), Json.object(read(mj)));
                } catch (Exception ignored) {
                    // reported by the mod loader
                }
            }
        }
        CarMap map = CarMap.load(root, facts, hmgapi);
        if (host && "1".equals(env("HMGAPI_TEST_ANY_CAR", ""))) return HookPlan.of(enabled, null);   // desktop tests: no map
        if (!map.runnable) return HookPlan.of(enabled, java.util.Collections.<String>emptySet(), offMissing(map));
        return HookPlan.of(enabled, map.points.keySet(), map.missing);
    }

    /** hooks off on this car: every catalogue point counts as missing, with the map's reason */
    Map<String, String> offMissing(CarMap map) {
        Map<String, String> m = new java.util.TreeMap<String, String>();
        try {
            for (String id : read(new File(root + "/data/hmgapi/maps/points.txt")).split("\n")) {
                if (!id.trim().isEmpty()) m.put(id.trim(), "hooks are off: " + map.reason);
            }
        } catch (Exception ignored) {
            // no catalogue list: mods' points read as unknown
        }
        return m;
    }

    /** whole-file replacements: the mod owns the file, other mods can't change it */
    static final String[] FILE_TYPES = { "delta", "app" };

    /** The whole-file change types a patches.json uses ("delta, app"), or null if none. */
    @SuppressWarnings("unchecked")
    static String fileChanges(Map<String, Object> patches) {
        Object list = patches == null ? null : patches.get("changes");
        if (!(list instanceof List)) return null;
        Set<String> used = new LinkedHashSet<String>();
        for (Object o : (List<Object>) list) {
            Object t = o instanceof Map ? ((Map<String, Object>) o).get("type") : null;
            for (String f : FILE_TYPES) if (f.equals(t)) used.add(f);
        }
        if (used.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String u : used) b.append(b.length() == 0 ? "" : ", ").append(u);
        return b.toString();
    }

    static String ownsWhy(String types) {
        return "replaces whole files (" + types + "), so no other mod can change those files."
                + " A hook in HMG-API lets mods share them (a missing hook can be added to HMG-API by pull request)";
    }
    final File data = new File(root + "/data/hmgapi");
    final File stockDir = new File(data, "stock");
    final File stateDir = new File(data, "state");
    final File stageDir = new File(data, "stage");
    final File prevDir = new File(stateDir, "prev");
    final File stateFile = new File(stateDir, "files.json");
    final File journalFile = new File(stateDir, "journal.json");

    final List<String> out = new ArrayList<String>();
    final List<String> problems = new ArrayList<String>();   // stop the apply
    String product, onebin;
    Map<String, Object> hmgapi = new LinkedHashMap<String, Object>();
    SystemCheck.Facts facts;

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null ? d : v;
    }

    public static void main(String[] args) {
        Engine e = new Engine();
        int rc;
        try {
            rc = e.run(args);
        } catch (Throwable t) {
            e.out.add("engine error: " + t);
            rc = 1;
        }
        if (!e.mirrors.isEmpty() || !e.restarts.isEmpty()) e.out.add("then    " + e.describeAfter());
        if (e.bindsChanged) e.out.add("then    binds (service restarts; adb may drop for a few seconds)");
        for (String l : e.out) System.out.println(l);
        System.out.flush();
        try {
            e.runAfter();
        } catch (Throwable t) {
            System.err.println("after: " + t);
        }
        System.exit(rc);
    }

    /** Live properties and service restarts, run after the result is printed. */
    final Map<String, String> mirrors = new LinkedHashMap<String, String>();
    final Set<String> restarts = new LinkedHashSet<String>();

    @SuppressWarnings("unchecked")
    void after(Map<String, Object> s, String value) {
        Object m = s.get("mirror");
        if (m != null) mirrors.put(String.valueOf(m), value);
        Object r = s.get("restart");
        if (r instanceof List) for (Object x : (List<Object>) r) restarts.add(String.valueOf(x));
        else if (r != null) restarts.add(String.valueOf(r));
    }

    String describeAfter() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> m : mirrors.entrySet()) b.append(m.getKey()).append(" = ").append(m.getValue()).append("; ");
        for (String r : restarts) b.append("restart ").append(r).append("; ");
        return b.toString().replaceAll("; $", "");
    }

    void runAfter() throws Exception {
        if (bindsChanged) {
            if (host) hostAction("binds");
            else {
                Run r = exec(env("HMGAPI_HMGAPID", "/system/bin/hmgapid"), "binds");
                System.out.println(r.text.trim());
            }
        }
        for (Map.Entry<String, String> m : mirrors.entrySet()) {
            if (!m.getKey().matches("[a-z][A-Za-z0-9._-]*") || m.getKey().startsWith("ro.")) continue;
            if (host) hostAction("live/" + m.getKey() + "=" + m.getValue());
            else exec("/system/bin/setprop", m.getKey(), m.getValue());
        }
        for (String svc : restarts) {
            if (!svc.matches("[a-z][a-z0-9_]*")) continue;
            if (host) {
                hostAction("restart " + svc);
                continue;
            }
            exec("/system/bin/setprop", "ctl.stop", svc);
            Thread.sleep(1000);
            exec("/system/bin/setprop", "ctl.start", svc);
        }
    }

    boolean bindsChanged;

    static String bindsText(Map<String, String> binds) {
        StringBuilder b = new StringBuilder();
        for (String k : binds.keySet()) b.append(k).append('\n');
        return b.toString();
    }

    String currentBinds() {
        try {
            File f = new File(stateDir, "binds.txt");
            return f.isFile() ? read(f) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** null if this bind may be done, else why not. */
    String bindRefused(String src, String target, String svc) {
        if (!src.matches("/data/hmgapi/mods/[a-z0-9][a-z0-9-]*/[A-Za-z0-9._/-]+") || src.contains("..")) {
            return "src must be a file in the mod's own folder";
        }
        if (!target.matches("/(sbin|system/bin)/[A-Za-z0-9._-]+")) return "target must be in /sbin or /system/bin";
        if (!svc.matches("[a-z0-9_]+")) return "service must be an init service name";
        for (String x : HMGAPI_OWNED) if (x.equals(target) || x.equals(src)) return target + " belongs to HMG-API's own install";
        for (String x : CRITICAL) {
            if (x.equals(target) && !allowedCritical(target)) {
                return "the unit needs " + target + " to boot (hmgapi.json overrides.allow_critical lists it to allow, at your own risk)";
            }
        }
        if ("/sbin/ueventd".equals(target) || "/sbin/healthd".equals(target) || target.equals("/init")) return target + " can't be swapped";
        return null;
    }

    void hostAction(String line) throws IOException {
        File f = new File(root + "/actions.log");
        String old = f.isFile() ? read(f) : "";
        write(f, old + line + "\n");
    }

    int run(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "status";
        boolean trial = false, force = false;
        for (int i = 1; i < args.length; i++) {
            if ("--trial".equals(args[i])) trial = true;
            else if ("--force".equals(args[i])) force = true;
        }
        stateDir.mkdirs();
        File lj = new File(data, "settings/hmgapi.json");
        if (lj.isFile()) hmgapi = Json.object(read(lj));
        product = env("HMGAPI_PRODUCT", null);
        if (product == null) product = getprop("ro.build.product");
        onebin = env("HMGAPI_ONEBIN", null);
        if (onebin == null) onebin = getprop("ro.mobis.onebindir");
        facts = new SystemCheck.Facts(root, (Map<String, Object>) hmgapi.get("car"));

        if ("status".equals(cmd)) return status();
        if ("verify-map".equals(cmd)) return MapCheck.run(this);
        if ("map-car".equals(cmd)) return MapProbe.run(this, args.length > 1 && "--write".equals(args[1]));
        if ("report".equals(cmd)) return Report.run(this, args.length > 1 && "--usb".equals(args[1]));
        if ("usb-scan".equals(cmd)) return UsbUpdates.scan(this);
        if ("publish-state".equals(cmd)) return LiveState.publish(this);
        if ("apps".equals(cmd)) return ModApps.sync(this);
        if ("probe".equals(cmd)) {   // a mod's install-time probe again (its answer is kept until then)
            if (args.length < 2 || !args[1].matches("[a-z0-9][a-z0-9-]*")) { out.add("usage: probe <mod id>"); return 2; }
            new File(stateDir, "probes/" + args[1] + ".json").delete();
            out.add("probe of " + args[1] + " will run at the next plan");
            return 0;
        }
        if ("update-hmgapi".equals(cmd)) {
            if (args.length < 2) {
                out.add("usage: engine update-hmgapi <zip> [--force]");
                return 2;
            }
            return UsbUpdates.updateHmgApi(this, args[1], force);
        }
        if ("map".equals(cmd)) {
            out.add(CarMap.load(root, facts, hmgapi).describe());
            return 0;
        }
        if ("install".equals(cmd) || "remove".equals(cmd)) {
            if (journalFile.exists()) {
                out.add("an earlier apply isn't finished or is a trial: run `engine keep` (trial) or `engine recover` first");
                return 1;
            }
            if (args.length < 2) {
                out.add("usage: engine install <zip|dir> [--uuid <uuid>] [--force] | remove <id>");
                return 2;
            }
            if ("remove".equals(cmd)) return Packages.remove(this, args[1]);
            String uuid = null;
            for (int i = 2; i + 1 < args.length; i++) if ("--uuid".equals(args[i])) uuid = args[i + 1];
            return Packages.install(this, args[1], uuid, force);
        }
        if ("recover".equals(cmd)) return recover();
        if ("keep".equals(cmd)) return keep();
        if (journalFile.exists() && !"keep".equals(cmd)) {
            out.add("an earlier apply isn't finished or is a trial: run `engine keep` (trial) or `engine recover` first");
            return 1;
        }
        boolean restoreAll = "restore".equals(cmd);
        // mods apply on any car (each mod's "system" says which it supports); without a good
        // hook map, their hooks don't load (the hook plan below says so per mod)
        if (!restoreAll) {
            CarMap map = CarMap.load(root, facts, hmgapi);
            if (!map.runnable) out.add("hooks are off on this car (" + map.reason + "): mods apply without their hooks");
        }
        if (!restoreAll && !"plan".equals(cmd) && !"apply".equals(cmd)) {
            out.add("usage: engine plan | apply [--trial] | keep | restore | status | recover | install <zip|dir> | remove <id> | map | map-car [--write] | verify-map | report [--usb] | usb-scan | update-hmgapi <zip> | publish-state | apps");
            return 2;
        }
        Plan p = plan(restoreAll, force);
        p.report(this);
        if (!restoreAll) {
            out.addAll(hookPlan().report());
            Set<String> on = new LinkedHashSet<String>();
            Map<String, Map<String, Object>> all = LiveState.mods(this, on);
            out.addAll(ModRuntime.describe(this, all, on));
            adbTcpWarning(all, on);
        }
        if (!problems.isEmpty()) {
            out.add("nothing changed: " + problems.size() + " problem(s)");
            for (String s : problems) out.add("  " + s);
            return 3;
        }
        if ("plan".equals(cmd)) return 0;
        if (p.isEmpty()) {
            out.add("nothing to do");
            if ("apply".equals(cmd)) LiveState.publish(this);   // a switch with no file change still reaches running apps
            return 0;
        }
        apply(p, trial);
        LiveState.publish(this);
        return 0;
    }

    // ------------------------------------------------------------------ mods

    static final class Mod {
        String id;
        File dir;
        Map<String, Object> json, patches, settings = new LinkedHashMap<String, Object>();
        Set<String> activeParts = new LinkedHashSet<String>();
        String set, version;
        List<Version.Dep> requires = new ArrayList<Version.Dep>(), conflicts = new ArrayList<Version.Dep>();
    }

    @SuppressWarnings("unchecked")
    List<Mod> mods() throws IOException {
        List<Mod> all = new ArrayList<Mod>();
        File[] dirs = new File(data, "mods").listFiles();
        if (dirs == null) return all;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            File pj = new File(d, "patches.json"), mj = new File(d, "mod.json");
            if (!d.isDirectory() || new File(d, ".disabled").exists() || !mj.isFile()) continue;
            Mod m = new Mod();
            m.id = d.getName();
            m.dir = d;
            try {
                m.json = Json.object(read(mj));
                if (!pj.isFile() && m.json.get("feature_flags") == null && m.json.get("probe") == null && m.json.get("wifi") == null) continue;   // nothing for the patch engine
                m.patches = pj.isFile() ? Json.object(read(pj)) : new LinkedHashMap<String, Object>();
                features(m);
                probe(m);
            } catch (IllegalArgumentException e) {
                problems.add(m.id + ": " + e.getMessage());
                continue;
            }
            String fc = fileChanges(m.patches);
            if (fc != null) out.add("note     " + m.id + " " + ownsWhy(fc));
            if (!m.id.equals(m.json.get("id"))) {
                problems.add(m.id + ": mod.json id doesn't match its folder");
                continue;
            }
            m.version = String.valueOf(m.json.containsKey("version") ? m.json.get("version") : "0");
            try {
                new Version(m.version);
                m.requires = Version.deps(m.json.get("requires"));
                m.conflicts = Version.deps(m.json.get("conflicts"));
                Object need = m.json.get("hmgapi");
                if (need != null && !Version.satisfies(HMGAPI_API, String.valueOf(need))) {
                    problems.add(m.id + " " + m.version + ": needs HMG-API " + need + " (this is " + HMGAPI_VERSION + ")");
                    continue;
                }
            } catch (IllegalArgumentException e) {
                problems.add(m.id + ": " + e.getMessage());
                continue;
            }
            String sys = SystemCheck.check(m.json.get("system"), facts);
            if (sys != null) {
                if (!SystemCheck.ignored(hmgapi, m.id)) {
                    problems.add(m.id + ": " + sys);
                    continue;
                }
                out.add("WARNING: " + m.id + ": " + sys + "; overrides.ignore_system is set, going on at your own risk");
            }
            if ("wireless-carplay-aa".equals(m.id) && SystemCheck.koreanIoniq5WifiUnconfirmed(facts)) {
                out.add("WARNING: Korean Ioniq 5 Wi-Fi hardware is unconfirmed: stock firmware may leave SDIO empty until the hotspot feature is restored. "
                        + "Some units have no Wi-Fi chip; check Engineering Mode for Wi-Fi module NONE or MAC 00:00:00:00:00:00. "
                        + "Wireless projection will only work with a chip.");
            }
            settings(m);
            Object parts = m.json.get("parts");
            if (parts instanceof List) {
                for (Object o : (List<Object>) parts) {
                    Map<String, Object> part = (Map<String, Object>) o;
                    if (when((String) part.get("when"), m.settings)) {
                        String ps = SystemCheck.check(part.get("system"), facts);
                        if (ps != null && !SystemCheck.ignored(hmgapi, m.id)) {
                            out.add(m.id + ": part " + part.get("id") + " left out: " + ps);
                            continue;
                        }
                        m.activeParts.add(String.valueOf(part.get("id")));
                    }
                }
            }
            Map<String, Object> cars = (Map<String, Object>) m.patches.get("cars");
            String best = null;
            if (cars != null && product != null) {
                for (String prefix : cars.keySet()) {
                    if (product.startsWith(prefix) && (best == null || prefix.length() > best.length())) best = prefix;
                }
            }
            if (cars != null && best == null) {
                problems.add(m.id + ": doesn't support this head unit (" + product + ")");
                continue;
            }
            m.set = best == null ? "" : String.valueOf(cars.get(best));
            all.add(m);
        }
        return order(all);
    }

    static final String FEATURE = "(com\\.hkmc|android\\.hardware|android\\.software)(\\.[a-z0-9_]+)+";

    /**
     * mod.json "feature_flags": Hyundai/Android feature flags the mod needs on
     * (not "system" "features", which is what the unit must already have)
     * (["com.hkmc.software.connectivity.carplay_wireless"]). Each becomes the same two
     * changes in every mod that asks: the permissions file
     * /system/etc/permissions/<name>.xml (byte for byte the same, so the engine
     * shares it; it stays while any mod wants it) and its featurexml.cfg line
     * commented out (the car deletes the file at boot while the line is live).
     */
    @SuppressWarnings("unchecked")
    void features(Mod m) throws IOException {
        Object f = m.json.get("feature_flags");
        Object wifi = m.json.get("wifi");
        if (wifi instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) wifi).get("client"))) {
            // "wifi": {"client": true}: Hyundai's Wi-Fi client (Tilepad's toggle, the Wireless mod's
            // opt-in): the android.hardware.wifi feature and persist.wifi.state=3 (client + wireless;
            // client and hotspot share one channel on these units). The same for every mod: merged.
            List<Object> l = f instanceof List ? new ArrayList<Object>((List<Object>) f) : new ArrayList<Object>();
            if (!l.contains("android.hardware.wifi")) l.add("android.hardware.wifi");
            m.json.put("feature_flags", f = l);
            List<Object> changes = (List<Object>) m.patches.get("changes");
            if (changes == null) m.patches.put("changes", changes = new ArrayList<Object>());
            Map<String, Object> prop = new LinkedHashMap<String, Object>();
            prop.put("type", "prop");
            prop.put("key", "persist.wifi.state");
            prop.put("value", "3");
            changes.add(prop);
        } else if (wifi != null) {
            throw new IllegalArgumentException("\"wifi\": {\"client\": true}");
        }
        if (f == null) return;
        if (!(f instanceof List)) throw new IllegalArgumentException("\"feature_flags\" is a list of feature names");
        List<Object> changes = (List<Object>) m.patches.get("changes");
        if (changes == null) m.patches.put("changes", changes = new ArrayList<Object>());
        List<Object> lines = new ArrayList<Object>();
        File dir = new File(m.dir, ".features");
        for (Object o : (List<Object>) f) {
            String name = String.valueOf(o);
            if (!name.matches(FEATURE)) throw new IllegalArgumentException("feature \"" + name + "\": a com.hkmc.*, android.hardware.* or android.software.* name");
            dir.mkdirs();
            File x = new File(dir, name + ".xml");
            String text = "<permissions>\n    <feature name=\"" + name + "\" />\n</permissions>\n";
            if (!(x.isFile() && read(x).equals(text))) write(x, text);
            Map<String, Object> add = new LinkedHashMap<String, Object>();
            add.put("type", "add");
            add.put("path", "/system/etc/permissions/" + name + ".xml");
            add.put("src", ".features/" + name + ".xml");
            add.put("mode", "644");
            add.put("stock", "any");
            changes.add(add);
            lines.add("-/system/etc/permissions/" + name + ".xml");
        }
        if (lines.isEmpty()) return;
        Map<String, Object> cfg = new LinkedHashMap<String, Object>();
        cfg.put("type", "lines");
        cfg.put("path", "/system/onebin/{onebin}/featurexml.cfg");
        cfg.put("stock", "any");
        cfg.put("comment", lines);
        changes.add(cfg);
    }

    /**
     * mod.json "probe": {"script": "probe.sh"} or {"jar": "probe.jar", "class": "x.Probe"}:
     * a mod's install-time step that looks at the unit (Codec Patch: which codecs
     * Android can't instantiate) and prints {"changes": [...]} on stdout. Only
     * "codecs" changes and "add"s of the mod's own files are taken. It runs once per
     * mod version and firmware, against the unit as it was before the mod's changes,
     * and its answer is kept (state/probes/<mod>.json; engine probe <mod> runs it
     * again). Its stderr goes to logs/<mod>/probe.log; it's stopped after 2 minutes.
     */
    @SuppressWarnings("unchecked")
    void probe(Mod m) throws IOException {
        Object po = m.json.get("probe");
        if (po == null) return;
        if (!(po instanceof Map)) throw new IllegalArgumentException("\"probe\": {\"script\": ...} or {\"jar\": ..., \"class\": ...}");
        Map<String, Object> p = (Map<String, Object>) po;
        String script = (String) p.get("script"), jar = (String) p.get("jar"), cls = (String) p.get("class");
        String rel = script != null ? script : jar;
        if (rel == null || !rel.matches("[A-Za-z0-9_][A-Za-z0-9._/-]*") || rel.contains("..") || (jar != null && (cls == null || !cls.matches("[A-Za-z_$][A-Za-z0-9_$.]*")))) {
            throw new IllegalArgumentException("probe: a script, or a jar with its class, in the mod");
        }
        String key = m.json.get("version") + "|" + product + "|" + facts.get("firmware");
        File cache = new File(stateDir, "probes/" + m.id + ".json");
        List<Object> changes = null;
        if (cache.isFile()) {
            Map<String, Object> c = Json.object(read(cache));
            if (key.equals(c.get("key"))) changes = (List<Object>) c.get("changes");
        }
        if (changes == null) {
            File f = new File(m.dir, rel);
            if (!f.isFile()) throw new IllegalArgumentException("probe: no " + rel + " in the mod");
            List<String> cmd = new ArrayList<String>();
            if (script != null) {
                cmd.add(host ? "/bin/sh" : "/system/bin/sh");
                cmd.add(f.getPath());
            } else if (host) {
                cmd.add("java"); cmd.add("-cp"); cmd.add(f.getPath()); cmd.add(cls);
            } else {
                cmd.add("/system/bin/app_process"); cmd.add("/system/bin"); cmd.add(cls);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("HMGAPI_MOD", m.id);
            pb.environment().put("HMGAPI_MOD_DIR", m.dir.getPath());
            if (jar != null && !host) pb.environment().put("CLASSPATH", f.getPath());
            File log = new File(data, "logs/" + m.id + "/probe.log");
            log.getParentFile().mkdirs();
            final Process pr = pb.start();
            final OutputStream logOut = new FileOutputStream(log);
            Thread err = new Thread() {   // stderr to the log (no ProcessBuilder.Redirect on the unit's Android 4.4)
                @Override
                public void run() {
                    try {
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = pr.getErrorStream().read(b)) > 0) logOut.write(b, 0, n);
                    } catch (IOException e) {
                        // the probe ended
                    }
                }
            };
            err.start();
            Thread kill = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(120000);
                        pr.destroy();
                    } catch (InterruptedException e) {
                        // done in time
                    }
                }
            };
            kill.setDaemon(true);
            kill.start();
            String text;
            int code;
            try {
                text = new String(readAll(pr.getInputStream()), "UTF-8");
                code = pr.waitFor();
            } catch (InterruptedException e) {
                throw new IOException("probe interrupted");
            } finally {
                kill.interrupt();
                try {
                    err.join(2000);
                } catch (InterruptedException e) {
                    // closing anyway
                }
                logOut.close();
            }
            if (code != 0) throw new IllegalArgumentException("probe failed (exit " + code + "; " + log.getPath() + ")");
            Object got;
            try {
                got = Json.object(text.trim()).get("changes");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("probe printed no {\"changes\": [...]}");
            }
            if (!(got instanceof List)) throw new IllegalArgumentException("probe printed no {\"changes\": [...]}");
            changes = (List<Object>) got;
            for (Object o : changes) {
                Map<String, Object> c = o instanceof Map ? (Map<String, Object>) o : null;
                String type = c == null ? null : String.valueOf(c.get("type"));
                String src = c == null ? null : (String) c.get("src");
                if (!"codecs".equals(type) && !("add".equals(type) && src != null && src.matches("[A-Za-z0-9_][A-Za-z0-9._/-]*") && !src.contains(".."))) {
                    throw new IllegalArgumentException("probe: only \"codecs\" changes and \"add\"s of the mod's own files (got " + type + ")");
                }
            }
            Map<String, Object> keep = new LinkedHashMap<String, Object>();
            keep.put("key", key);
            keep.put("changes", changes);
            cache.getParentFile().mkdirs();
            write(cache, Json.write(keep));
            out.add("probe    " + m.id + ": " + changes.size() + " change(s) from its look at this unit");
        }
        List<Object> all = (List<Object>) m.patches.get("changes");
        if (all == null) m.patches.put("changes", all = new ArrayList<Object>());
        all.addAll(changes);
    }

    /** requires (with their version ranges) and conflicts checked; requires first, then by id (stable). */
    List<Mod> order(List<Mod> all) {
        Map<String, Mod> byId = new LinkedHashMap<String, Mod>();
        for (Mod m : all) byId.put(m.id, m);
        for (Mod m : all) {
            for (Version.Dep c : m.conflicts) {
                Mod other = byId.get(c.id);
                if (other != null && Version.satisfies(other.version, c.range)) {
                    problems.add("conflict: " + m.id + " " + m.version + " can't be used with " + other.id + " " + other.version
                            + " (its conflicts list: " + c + ")");
                }
            }
        }
        List<Mod> out = new ArrayList<Mod>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Mod m : all) visit(m, byId, seen, out, new LinkedHashSet<String>());
        return out;
    }

    void visit(Mod m, Map<String, Mod> byId, Set<String> seen, List<Mod> out, Set<String> path) {
        if (seen.contains(m.id)) return;
        if (!path.add(m.id)) {
            problems.add(m.id + ": requires loop");
            return;
        }
        for (Version.Dep r : m.requires) {
            Mod dep = byId.get(r.id);
            if (dep == null) problems.add(m.id + ": requires " + r + ", which isn't installed and enabled");
            else if (!Version.satisfies(dep.version, r.range)) {
                problems.add(m.id + ": requires " + r + ", but " + dep.id + " " + dep.version + " is installed");
            } else visit(dep, byId, seen, out, path);
        }
        seen.add(m.id);
        out.add(m);
    }

    void settings(Mod m) throws IOException {
        m.settings.putAll(settingsOf(m.dir, data, m.id));
    }

    /** a mod's settings: its settings.json defaults, then the owner's settings/<id>.json */
    @SuppressWarnings("unchecked")
    static Map<String, Object> settingsOf(File modDir, File data, String id) throws IOException {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        File sj = new File(modDir, "settings.json");
        if (sj.isFile()) {
            Object list = Json.object(read(sj)).get("settings");
            if (list instanceof List) {
                for (Object o : (List<Object>) list) {
                    Map<String, Object> s = (Map<String, Object>) o;
                    if (s.containsKey("key") && s.containsKey("default")) out.put((String) s.get("key"), s.get("default"));
                }
            }
        }
        File own = new File(data, "settings/" + id + ".json");
        if (own.isFile()) out.putAll(Json.object(read(own)));
        return out;
    }

    /**
     * A part's or a binding's "when": none (always), "settings.k" (k is on), "!settings.k"
     * (k is off), "settings.k=v" (k is v, for a choice), "!settings.k=v".
     */
    static boolean when(String when, Map<String, Object> settings) {
        if (when == null) return true;
        boolean not = when.startsWith("!");
        if (not) when = when.substring(1);
        if (when.startsWith("settings.")) when = when.substring(9);
        int eq = when.indexOf('=');
        boolean holds = eq < 0 ? truthy(settings.get(when)) : String.valueOf(settings.get(when.substring(0, eq))).equals(when.substring(eq + 1));
        return holds != not;
    }

    static boolean truthy(Object v) {
        return v instanceof Boolean ? (Boolean) v : v != null && !"".equals(v) && !"0".equals(String.valueOf(v));
    }

    // ------------------------------------------------------------------ plan

    /** One mod's change to one path. */
    static final class Ch {
        Mod mod;
        Map<String, Object> c;
        String type;

        String src(String key) {
            Object v = c.get(key);
            if (v instanceof List) v = ((List<?>) v).isEmpty() ? null : ((List<?>) v).get(0);
            return v == null ? null : new File(mod.dir, String.valueOf(v).replace("{set}", mod.set)).getPath();
        }

        /** "delta": one .lpd or a list (one per stock version: the first that fits is used). */
        List<String> srcs(String key) {
            List<String> out = new ArrayList<String>();
            Object v = c.get(key);
            if (v instanceof List) for (Object o : (List<?>) v) out.add(new File(mod.dir, String.valueOf(o).replace("{set}", mod.set)).getPath());
            else if (v != null) out.add(src(key));
            return out;
        }

        boolean optional() {
            return Boolean.TRUE.equals(c.get("optional"));
        }
    }

    static final class Target {
        String path;             // as on the head unit, e.g. /system/lib/x.so
        List<Ch> changes = new ArrayList<Ch>();
        File stage;              // built result; null = the file should not exist
        boolean exists;          // result exists
        String result;           // md5 of the result, or "absent"
        String stock;            // md5 of the stock original, or "absent"
        File baseline;           // where the stock bytes come from (null = absent)
        boolean newBackup;       // baseline must be backed up at apply
        boolean app;             // rebuilt app: odex regenerated
        File odexSrc;            // app: the stock odex its delta was built from (backed up at apply)
        Set<String> mods = new LinkedHashSet<String>();
        boolean restore;         // no mod wants it any more: back to stock
    }

    final class Plan {
        Map<String, Target> targets = new TreeMap<String, Target>();
        Map<String, Object[]> settings = new TreeMap<String, Object[]>();   // "ns/key" -> {value, mod}
        Map<String, String> binds = new TreeMap<String, String>();   // "src target service" -> mod
        Map<String, Object> state;
        Set<String> settingsRestore = new LinkedHashSet<String>();
        Map<String, String> rebased = new TreeMap<String, String>();   // path -> live md5: changed outside HMG-API, built on again
        Map<String, String> rebasedFrom = new TreeMap<String, String>();   // path -> what HMG-API had written (merges: checked after build)

        boolean isEmpty() {
            if (!rebased.isEmpty()) return false;
            for (Target t : targets.values()) if (t.restore || t.result == null || !t.result.equals(owned(t.path))) return false;
            for (String k : settings.keySet()) if (!String.valueOf(settings.get(k)[0]).equals(ownedSetting(k))) return false;
            return settingsRestore.isEmpty() && bindsText(binds).equals(currentBinds());
        }

        @SuppressWarnings("unchecked")
        String owned(String path) {
            Map<String, Object> f = (Map<String, Object>) ((Map<String, Object>) state.get("files")).get(path);
            return f == null ? null : (String) f.get("result");
        }

        @SuppressWarnings("unchecked")
        String ownedSetting(String k) {
            Map<String, Object> s = (Map<String, Object>) ((Map<String, Object>) state.get("settings")).get(k);
            return s == null ? null : String.valueOf(s.get("value"));
        }

        void report(Engine e) {
            for (Target t : targets.values()) {
                String now = owned(t.path);
                if (t.result == null && !t.restore) e.out.add("problem  " + t.path + " " + t.mods);
                else if (t.restore) e.out.add("restore  " + t.path + " (stock)");
                else if (t.result.equals(now)) e.out.add("same     " + t.path + " " + t.mods);
                else e.out.add((now == null ? "change   " : "update   ") + t.path + " " + t.mods + " -> " + t.result
                        + "   [" + restart(t.path) + "]");
            }
            for (Map.Entry<String, Object[]> s : settings.entrySet()) {
                String v = String.valueOf(s.getValue()[0]);
                e.out.add((v.equals(ownedSetting(s.getKey())) ? "same     setting " : "setting  ") + s.getKey() + " = "
                        + (v.isEmpty() ? "(unset)" : v) + " [" + s.getValue()[1] + "]");
            }
            for (String k : settingsRestore) e.out.add("setting  " + k + " back to stock");
            String now = currentBinds();
            for (Map.Entry<String, String> b : binds.entrySet()) {
                String[] w = b.getKey().split(" ");
                e.out.add((now.contains(b.getKey() + "\n") ? "same     bind " : "bind     ") + w[0] + " over " + w[1]
                        + " (service " + w[2] + ") [" + b.getValue() + "]   [applied now, and at every start]");
            }
            for (String l : now.split("\n")) {
                if (!l.isEmpty() && !binds.containsKey(l)) e.out.add("unbind   " + l.split(" ")[1] + " (stock again)");
            }
        }
    }

    /** What a change to this path needs before it is used. */
    static String restart(String path) {
        if (path.startsWith("/system/app/") || path.startsWith("/system/priv-app/")) return "restart the app";
        return "restart the head unit";
    }

    @SuppressWarnings("unchecked")
    Plan plan(boolean restoreAll, boolean force) throws Exception {
        Plan p = new Plan();
        p.state = loadState();
        Map<String, Object> files = (Map<String, Object>) p.state.get("files");
        Map<String, Object> st = (Map<String, Object>) p.state.get("settings");

        // files HMG-API owns must still be what it wrote (changed outside: settled below, once the mods' changes are known)
        Map<String, String> outside = new TreeMap<String, String>();
        for (Map.Entry<String, Object> e : files.entrySet()) {
            Map<String, Object> f = (Map<String, Object>) e.getValue();
            String live = md5(sys(e.getKey()));
            if (!live.equals(f.get("result")) && !force) outside.put(e.getKey(), live);
        }

        List<Mod> mods = restoreAll ? new ArrayList<Mod>() : mods();
        for (Mod m : mods) {
            Object list = m.patches.get("changes");
            if (!(list instanceof List)) continue;
            for (Object o : (List<Object>) list) {
                Map<String, Object> c = (Map<String, Object>) o;
                String part = (String) c.get("part");
                if (part != null && !m.activeParts.contains(part)) continue;
                Ch ch = new Ch();
                ch.mod = m;
                ch.c = c;
                ch.type = String.valueOf(c.get("type"));
                if ("bind".equals(ch.type)) {
                    String rel = String.valueOf(c.get("src")), target = String.valueOf(c.get("target")),
                            svc = String.valueOf(c.get("service"));
                    // src: a file in the mod's own folder (as the head unit sees it)
                    String src = "/data/hmgapi/mods/" + m.id + "/" + rel;
                    if (!new File(m.dir, rel).isFile() && !rel.contains("..")) problems.add(m.id + ": bind: no " + rel + " in the mod");
                    String why = bindRefused(src, target, svc);
                    if (why != null) problems.add(m.id + ": bind " + target + ": " + why);
                    else p.binds.put(src + " " + target + " " + svc, m.id);
                    continue;
                }
                if ("prop".equals(ch.type)) {
                    String key = String.valueOf(c.get("key"));
                    if (!key.startsWith("persist.") || !key.matches("[A-Za-z0-9._-]+")) {
                        problems.add(m.id + ": prop " + key + ": only persist.* properties can be set (they're kept in /data/property;"
                                + " ro.* can't change, and build.prop edits aren't used for properties)");
                        continue;
                    }
                }
                if ("setting".equals(ch.type) && c.get("add") instanceof List) {
                    // a list setting (enabled_input_methods "a:b"): every mod's entries on top of the stock value
                    String k = c.get("namespace") + "/" + c.get("key");
                    String sep = c.containsKey("separator") ? String.valueOf(c.get("separator")) : ":";
                    if (!sep.matches("[:;,|]")) {
                        problems.add(m.id + ": setting " + k + ": separator is one of : ; , |");
                        continue;
                    }
                    Object[] prev = p.settings.get(k);
                    if (prev != null && !(prev[2] instanceof Map && ((Map<String, Object>) prev[2]).get("add") instanceof List)) {
                        problems.add("conflict: setting " + k + ": " + prev[1] + " sets it to " + prev[0] + ", " + m.id + " adds to it");
                        continue;
                    }
                    List<String> entries = new ArrayList<String>();
                    if (prev != null) entries.addAll(java.util.Arrays.asList(String.valueOf(prev[0]).split(java.util.regex.Pattern.quote(sep))));
                    else {
                        Map<String, Object> was = (Map<String, Object>) st.get(k);
                        String base = was != null ? (was.get("stock") == null ? "" : String.valueOf(was.get("stock"))) : settingGet(k);
                        if (base != null && !base.isEmpty() && !"null".equals(base)) entries.addAll(java.util.Arrays.asList(base.split(java.util.regex.Pattern.quote(sep))));
                    }
                    for (Object x : (List<Object>) c.get("add")) if (!entries.contains(String.valueOf(x))) entries.add(String.valueOf(x));
                    StringBuilder v = new StringBuilder();
                    for (String x : entries) if (!x.isEmpty()) v.append(v.length() == 0 ? "" : sep).append(x);
                    p.settings.put(k, new Object[] { v.toString(), prev == null ? m.id : prev[1] + "," + m.id, c });
                    continue;
                }
                if ("setting".equals(ch.type) || "prop".equals(ch.type)) {
                    String k = "prop".equals(ch.type) ? "prop/" + c.get("key") : c.get("namespace") + "/" + c.get("key");
                    Object[] prev = p.settings.get(k);
                    if ("setting".equals(ch.type) && prev != null && prev[2] instanceof Map && ((Map<String, Object>) prev[2]).get("add") instanceof List) {
                        problems.add("conflict: setting " + k + ": " + prev[1] + " adds to it, " + m.id + " sets it to " + c.get("value"));
                        continue;
                    }
                    String v = String.valueOf(c.get("value"));
                    if (prev != null && !prev[0].equals(v) && !prev[1].equals(m.id)) {
                        problems.add("conflict: setting " + k + ": " + prev[1] + " wants " + prev[0] + ", " + m.id + " wants " + v);
                    }
                    p.settings.put(k, new Object[] { v, m.id, c });
                    continue;
                }
                String path = String.valueOf(c.get("path")).replace("{onebin}", onebin == null ? "" : onebin);
                if (!systemPath(path)) {
                    problems.add(m.id + ": \"" + c.get("path") + "\" isn't a plain /system file path (letters, digits, . _ + - and /;"
                            + " no .. or //): a change's path must say plainly which file it is");
                    continue;
                }
                Target t = p.targets.get(path);
                if (t == null) {
                    t = new Target();
                    t.path = path;
                    p.targets.put(path, t);
                }
                t.changes.add(ch);
                t.mods.add(m.id);
            }
        }
        for (Map.Entry<String, String> o : outside.entrySet()) {
            String why = rebaseWhy(o.getKey(), o.getValue(), (Map<String, Object>) files.get(o.getKey()), p.targets.get(o.getKey()));
            if (why == null) {
                problems.add(o.getKey() + ": changed outside HMG-API (expected " + ((Map<String, Object>) files.get(o.getKey())).get("result")
                        + ", found " + o.getValue() + "); run `engine status`, or apply --force to rebuild it anyway");
                continue;
            }
            // taken as found: built on again as a file HMG-API hasn't changed yet (its old backup replaced at apply)
            if (why.startsWith("the mods' changes merge")) p.rebasedFrom.put(o.getKey(), String.valueOf(((Map<String, Object>) files.get(o.getKey())).get("result")));
            files.remove(o.getKey());
            p.rebased.put(o.getKey(), o.getValue());
            out.add("rebase   " + o.getKey() + ": changed outside HMG-API (now " + o.getValue() + ", " + why + ")"
                    + (p.targets.containsKey(o.getKey()) ? "; the mods' changes go on it" : "; no mod changes it now, so HMG-API lets it go"));
        }
        rebaseAccept = p.rebased;
        for (String path : files.keySet()) {
            if (!p.targets.containsKey(path)) {
                Target t = new Target();
                t.path = path;
                t.restore = true;
                p.targets.put(path, t);
            }
        }
        for (String k : st.keySet()) if (!p.settings.containsKey(k)) p.settingsRestore.add(k);

        rm(stageDir);
        stageDir.mkdirs();
        List<String> skipped = new ArrayList<String>();
        for (Target t : p.targets.values()) {
            if (t.restore) {
                Map<String, Object> f = (Map<String, Object>) files.get(t.path);
                t.stock = (String) f.get("stock");
                t.result = t.stock;
                t.app = Boolean.TRUE.equals(f.get("app"));
                continue;
            }
            int before = problems.size();
            build(t, (Map<String, Object>) files.get(t.path));
            boolean opt = !t.changes.isEmpty();
            for (Ch ch : t.changes) opt &= ch.optional();
            if (opt && problems.size() > before) {
                // optional (e.g. a fix only for some stock versions): left out, the rest goes on
                List<String> why = new ArrayList<String>(problems.subList(before, problems.size()));
                problems.subList(before, problems.size()).clear();
                out.add("skip     " + t.path + " (optional, " + t.mods + "): " + why.get(0));
                skipped.add(t.path);
            }
        }
        // a merge built on a file that changed outside: only if HMG-API's own edits are gone from it (the whole file
        // put back, as a NEMESIS uninstall does). If building changes nothing, they're still in it, and its stock
        // can't be told apart from them.
        for (Map.Entry<String, String> r : p.rebasedFrom.entrySet()) {
            Target t = p.targets.get(r.getKey());
            if (t != null && p.rebased.get(r.getKey()).equals(t.result)) {
                for (java.util.Iterator<String> it = out.iterator(); it.hasNext(); ) if (it.next().startsWith("rebase   " + r.getKey() + ":")) it.remove();
                problems.add(r.getKey() + ": changed outside HMG-API (expected " + r.getValue() + ", found " + t.result
                        + "), and HMG-API's own changes are still in it; run `engine status`, or apply --force to rebuild it anyway");
            }
        }
        for (String path : skipped) {
            Map<String, Object> f = (Map<String, Object>) files.get(path);
            if (f == null) p.targets.remove(path);
            else {   // owned before, can't be built now: back to stock
                Target t = p.targets.get(path);
                t.restore = true;
                t.stock = (String) f.get("stock");
                t.result = t.stock;
                t.app = Boolean.TRUE.equals(f.get("app"));
            }
        }
        return p;
    }

    /** live md5s taken as found this plan (Plan.rebased), for build() */
    Map<String, String> rebaseAccept = new TreeMap<String, String>();

    /** change types that edit whatever file is there (merged, keeping every other line) */
    static final Set<String> MERGES = new LinkedHashSet<String>(java.util.Arrays.asList("lines", "props", "codecs"));

    /**
     * A file HMG-API changed was changed since by something else (a NEMESIS package
     * installed or uninstalled, an update): why it can be built on as found, or null.
     * Changes that merge onto any file ("stock": "any") always can; exact ones only if
     * the file is now a stock the mods accept, the stock HMG-API recorded, or a stock
     * copy NEMESIS keeps.
     */
    @SuppressWarnings("unchecked")
    String rebaseWhy(String path, String live, Map<String, Object> owned, Target t) throws IOException {
        if ("absent".equals(live)) return null;
        if (t != null && !t.changes.isEmpty()) {
            boolean merge = true;
            for (Ch ch : t.changes) merge &= MERGES.contains(ch.type) && "any".equals(ch.c.get("stock"));
            if (merge) return "the mods' changes merge onto any version of it";
        }
        if (live.equals(owned.get("stock"))) return "the stock HMG-API started from";
        if (t != null) {
            Set<String> accepted = new LinkedHashSet<String>();
            for (Ch ch : t.changes) {
                Object st = ch.c.get("stock");
                if (st instanceof String && !"any".equals(st)) accepted.add((String) st);
                else if (st instanceof List) for (Object o : (List<Object>) st) accepted.add(String.valueOf(o));
                accepted.addAll(setStock(ch.mod, path));
            }
            if (accepted.contains(live)) return "a stock version the mods accept";
        }
        if (nemesisStock(path, java.util.Collections.singleton(live)) != null) return "the stock copy a NEMESIS package keeps";
        return null;
    }

    /** the NEMESIS package that keeps a backup of this path (or marks it absent before it): its folder name, or null */
    String nemesisPackageOf(String path) {
        File[] pkgs = new File(root + "/data/nemesis/backup").listFiles();
        if (pkgs == null) return null;
        java.util.Arrays.sort(pkgs);
        for (File pkg : pkgs) if (pkg.isDirectory() && hasBackupOf(pkg, path, 4)) return pkg.getName();
        return null;
    }

    boolean hasBackupOf(File dir, String path, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth < 0) return false;
        for (File f : fs) {
            if (f.isDirectory() ? hasBackupOf(f, path, depth - 1) : backupName(f.getName(), path)) return true;
        }
        return false;
    }

    /**
     * Whether a file in a NEMESIS package's backup folder is (or marks) this path. Each
     * package names its own: x.so, x.so.absent (absent before), perm.<x>.xml,
     * featurexml.<onebin>.cfg for /system/onebin/<onebin>/featurexml.cfg.
     */
    static boolean backupName(String n, String path) {
        String name = new File(path).getName();
        if (n.endsWith(".absent")) n = n.substring(0, n.length() - 7);
        if (n.equals(name) || n.endsWith("." + name) || n.startsWith(name)) return true;
        int dot = name.lastIndexOf('.');
        String parent = new File(path).getParentFile() == null ? "" : new File(path).getParentFile().getName();
        return dot > 0 && !parent.isEmpty() && n.equals(name.substring(0, dot) + "." + parent + name.substring(dot));
    }

    // ------------------------------------------------------------------ build

    /** Files the unit can't boot (or HMG-API can't run) without. */
    static final String[] CRITICAL = {
        "/system/bin/linker", "/system/bin/sh", "/system/bin/mksh", "/system/bin/toolbox", "/system/bin/servicemanager",
        "/system/bin/vold", "/system/bin/surfaceflinger", "/system/bin/dexopt", "/system/bin/installd",
        "/system/lib/libc.so", "/system/lib/libdl.so", "/system/lib/libm.so", "/system/lib/libstdc++.so", "/system/lib/liblog.so",
        "/system/lib/libcutils.so", "/system/lib/libutils.so", "/system/lib/libbinder.so", "/system/lib/libdvm.so",
        "/system/lib/libandroid_runtime.so", "/system/lib/libnativehelper.so", "/system/lib/libselinux.so",
        "/system/etc/init.sh", "/system/etc/mkshrc",
    };
    /** Boot class path jars (and their odex): Dalvik won't start without them intact. */
    static final String[] BOOT_JARS = { "core", "conscrypt", "okhttp", "core-junit", "bouncycastle", "ext", "framework",
        "framework2", "telephony-common", "voip-common", "mms-common", "android.policy", "services", "apache-xml",
        "webviewchromium", "automotive" };
    /** HMG-API's installer owns these; a mod changing them would fight it. */
    static final String[] HMGAPI_OWNED = { "/system/bin/app_process", "/system/bin/app_process.hmgapi-stock",
        "/system/bin/hmgapid", "/system/bin/lpatch", "/system/lib/libhmgapi_hook.so", "/system/framework/hmgapi-hook.jar",
        "/system/etc/install-recovery.sh", "/system/etc/qb_runtime.sh" };

    /** null if mods may change this file this way, else why not. */
    @SuppressWarnings("unchecked")
    /**
     * A mod's file path: a plain /system path, so the plan shows the real file and the
     * checks on HMG-API's own and boot-critical files (exact paths) can't be stepped
     * around (/system/bin/../bin/app_process), and nothing a shell would read specially.
     */
    static boolean systemPath(String path) {
        return path.matches("/system/[A-Za-z0-9._+-]+(/[A-Za-z0-9._+-]+)*") && !path.contains("/../") && !path.endsWith("/..")
                && !path.contains("/./") && !path.endsWith("/.");
    }

    String guard(Target t) {
        for (String x : HMGAPI_OWNED) if (x.equals(t.path)) return t.path + ": belongs to HMG-API's own install; mods can't change it";
        for (Ch ch : t.changes) {
            Object mode = ch.c.get("mode");
            if (mode == null) continue;
            String ms = String.valueOf(mode);
            if (!ms.matches("0?[0-7]{3}")) {
                return t.path + ": " + ch.mod.id + ": mode " + ms + " (only plain modes like 644 or 755: no setuid, setgid or sticky bits)";
            }
        }
        if (t.path.equals("/system/build.prop")) {
            for (Ch ch : t.changes) {
                if (!"props".equals(ch.type)) {
                    return t.path + ": " + ch.mod.id + " uses a \"" + ch.type + "\" change; build.prop only takes \"props\" (key edits),"
                            + " because a broken build.prop can keep the unit from booting";
                }
                List<String> keys = new ArrayList<String>();
                Object set = ch.c.get("set"), rm = ch.c.get("remove");
                if (set instanceof Map) keys.addAll(((Map<String, Object>) set).keySet());
                if (rm instanceof List) for (Object k : (List<Object>) rm) keys.add(String.valueOf(k));
                for (String k : keys) {
                    if (k.startsWith("ro.") && !allowedCritical(t.path + ":" + k)) {
                        return t.path + ": " + ch.mod.id + " changes " + k + "; ro.* properties set how the firmware identifies and"
                                + " boots itself, so they're refused (hmgapi.json overrides.allow_critical \"" + t.path + ":" + k
                                + "\" allows it, at your own risk)";
                    }
                    if (!k.matches("[A-Za-z0-9._-]+")) return t.path + ": " + ch.mod.id + ": \"" + k + "\" isn't a property name";
                }
                if (set instanceof Map) {
                    for (Object v : ((Map<String, Object>) set).values()) {
                        String sv = String.valueOf(v);
                        if (sv.indexOf('\n') >= 0 || sv.indexOf('\r') >= 0) return t.path + ": " + ch.mod.id + ": a value with a line break";
                    }
                }
            }
            return null;
        }
        boolean critical = false;
        for (String x : CRITICAL) critical |= x.equals(t.path);
        for (String j : BOOT_JARS) {
            critical |= t.path.equals("/system/framework/" + j + ".jar") || t.path.equals("/system/framework/" + j + ".odex");
        }
        if (critical && !allowedCritical(t.path)) {
            return t.path + ": the unit needs this file to boot, so mods can't change it (hmgapi.json overrides.allow_critical"
                    + " lists paths allowed anyway, at your own risk)";
        }
        if (critical) out.add("WARNING: " + t.path + " is boot-critical; changing it because overrides.allow_critical lists it");
        return null;
    }

    @SuppressWarnings("unchecked")
    boolean allowedCritical(String what) {
        Object o = hmgapi.get("overrides");
        if (!(o instanceof Map)) return false;
        Object l = ((Map<String, Object>) o).get("allow_critical");
        return l instanceof List && ((List<Object>) l).contains(what);
    }

    @SuppressWarnings("unchecked")
    void build(Target t, Map<String, Object> owned) throws Exception {
        String refused = guard(t);
        if (refused != null) {
            problems.add(refused);
            return;
        }
        File live = sys(t.path);
        // the stock original
        Set<String> accepted = new LinkedHashSet<String>();
        boolean any = false;
        for (Ch ch : t.changes) {
            Object s = ch.c.get("stock");
            if ("any".equals(s)) any = true;
            else if (s instanceof String) accepted.add((String) s);
            else if (s instanceof List) for (Object o : (List<Object>) s) accepted.add(String.valueOf(o));
            accepted.addAll(setStock(ch.mod, t.path));
            if ("app".equals(ch.type)) t.app = true;
        }
        if (owned != null) {
            t.stock = (String) owned.get("stock");
            t.baseline = "absent".equals(t.stock) ? null : backup(t.path);
        } else {
            String m = md5(live);
            if ("absent".equals(m)) {
                boolean allAdd = true;
                for (Ch ch : t.changes) if (!"add".equals(ch.type)) allAdd = false;
                if (!allAdd) {
                    problems.add(t.path + ": not on this head unit, but " + t.mods + " change it");
                    return;
                }
                t.stock = "absent";
            } else if (any || accepted.contains(m) || m.equals(rebaseAccept.get(t.path)) || overrideAccepts(t.path, m)) {
                t.stock = m;
                t.baseline = live;
                t.newBackup = true;
            } else {
                File nem = nemesisStock(t.path, accepted);
                if (nem != null) {
                    t.stock = md5(nem);
                    t.baseline = nem;
                    t.newBackup = true;
                    out.add("note     " + t.path + ": live file isn't stock (" + m + "); using the stock copy NEMESIS kept ("
                            + nem.getParentFile().getName() + "). Uninstall that NEMESIS package, or HMG-API takes this file over.");
                } else {
                    boolean allAdd = true;
                    for (Ch ch : t.changes) allAdd &= "add".equals(ch.type);
                    String pkg = nemesisPackageOf(t.path);
                    String from = pkg != null ? "the NEMESIS package " + pkg + " changed it: uninstall " + pkg + " in NEMESIS Manager first"
                            : "if a NEMESIS package put it there, uninstall that package first";
                    if (allAdd) {
                        problems.add(t.path + ": already on the head unit (" + m + "), but " + t.mods + " add it; " + from
                                + ", or turn " + t.mods + " off so the other mods apply.");
                    } else {
                        problems.add(t.path + ": not the stock file " + t.mods + " expect (found " + m + ", accepted "
                                + accepted + "); " + from + ". hmgapi.json overrides.accept_baseline can accept it at your own risk.");
                    }
                    return;
                }
            }
        }
        // what may be combined
        List<Ch> excl = new ArrayList<Ch>();
        for (Ch ch : t.changes) {
            boolean replace = "add".equals(ch.type) && !"absent".equals(t.stock);
            if ("delta".equals(ch.type) || "app".equals(ch.type) || replace) excl.add(ch);
        }
        if (!excl.isEmpty()) {
            Mod owner = excl.get(excl.size() - 1).mod;   // within one mod the last active one wins
            for (Ch ch : t.changes) {
                if (ch.mod != owner) {
                    problems.add("conflict: " + t.path + ": " + owner.id + " replaces the whole file (" + excl.get(excl.size() - 1).type
                            + "), so " + ch.mod.id + " can't change it too");
                    return;
                }
            }
            Ch win = excl.get(excl.size() - 1);
            t.changes = new ArrayList<Ch>(Collections.singletonList(win));
        } else {
            // two mods adding the same new file: only if the bytes are the same
            String addMd5 = null;
            Ch first = null;
            for (Ch ch : t.changes) {
                if (!"add".equals(ch.type)) continue;
                String m = md5(new File(ch.src("src")));
                if (first == null) { first = ch; addMd5 = m; }
                else if (!m.equals(addMd5)) {
                    problems.add("conflict: " + t.path + ": " + first.mod.id + " and " + ch.mod.id + " add different files");
                    return;
                }
            }
        }

        File stage = new File(stageDir, t.path.replace('/', '_'));
        File work = new File(stageDir, t.path.replace('/', '_') + ".work");
        if (t.baseline == null) work.delete();
        else copy(t.baseline, work);
        Map<String, String[]> props = new LinkedHashMap<String, String[]>();   // key -> {value or null, mod}
        Map<String, String[]> lines = new LinkedHashMap<String, String[]>();   // line -> {op, mod}
        Map<String, Object[]> codecs = new LinkedHashMap<String, Object[]>();   // codec name -> {{kind, mime} or null (remove), mod}
        for (Ch ch : t.changes) {
            String type = ch.type;
            if ("add".equals(type)) {
                copy(new File(ch.src("src")), work);
            } else if ("props".equals(type)) {
                Map<String, Object> set = (Map<String, Object>) ch.c.get("set");
                if (set != null) for (Map.Entry<String, Object> e : set.entrySet()) mergeKey(props, t.path, e.getKey(), String.valueOf(e.getValue()), ch.mod.id);
                List<Object> rm = (List<Object>) ch.c.get("remove");
                if (rm != null) for (Object k : rm) mergeKey(props, t.path, String.valueOf(k), null, ch.mod.id);
            } else if ("codecs".equals(type)) {
                List<Object> rm = (List<Object>) ch.c.get("remove"), ad = (List<Object>) ch.c.get("add");
                if (rm != null) for (Object x : rm) mergeCodec(codecs, t.path, String.valueOf(x), null, ch.mod.id);
                if (ad != null) {
                    for (Object x : ad) {
                        Map<String, Object> e = x instanceof Map ? (Map<String, Object>) x : null;
                        String name = e == null ? null : (String) e.get("name"), mime = e == null ? null : (String) e.get("type");
                        String kind = e == null ? null : String.valueOf(e.containsKey("kind") ? e.get("kind") : "decoder");
                        if (name == null || mime == null || !name.matches(CODEC) || !mime.matches("[a-z0-9.+/-]+")
                                || !("decoder".equals(kind) || "encoder".equals(kind))) {
                            problems.add(t.path + ": " + ch.mod.id + ": a codec to add needs name, type (mime) and kind decoder|encoder");
                            return;
                        }
                        mergeCodec(codecs, t.path, name, new String[] { kind, mime }, ch.mod.id);
                    }
                }
            } else if ("lines".equals(type)) {
                for (String op : new String[] { "comment", "uncomment", "add", "remove" }) {
                    List<Object> l = (List<Object>) ch.c.get(op);
                    if (l != null) for (Object x : l) mergeLine(lines, t.path, String.valueOf(x), op, ch.mod.id);
                }
            } else if ("recipe".equals(type)) {
                File next = new File(work.getPath() + ".r");
                Run r = exec(lpatch, "apply", ch.src("recipe"), work.getPath(), next.getPath());
                if (r.code != 0 || !next.isFile()) {
                    problems.add((t.mods.size() > 1 ? "conflict: " : "") + t.path + ": " + ch.mod.id + "'s recipe doesn't fit"
                            + (t.mods.size() > 1 ? " (another mod changed the same bytes)" : "") + ": " + r.text.trim());
                    return;
                }
                if (!next.renameTo(work)) throw new IOException("rename " + next);
            } else if ("delta".equals(type)) {
                File next = new File(work.getPath() + ".d");
                String why = "";
                boolean fit = false;
                for (String d : ch.srcs("delta")) {
                    Run r = exec(lpatch, "delta", d, work.getPath(), next.getPath());
                    if (r.code == 0 && next.isFile()) { fit = true; break; }
                    why = r.text.trim();
                }
                if (!fit) {
                    problems.add(t.path + ": " + ch.mod.id + "'s delta doesn't fit: " + why);
                    return;
                }
                if (!next.renameTo(work)) throw new IOException("rename " + next);
            } else if ("app".equals(type)) {
                // delta from the stock odex -> classes.dex, added to the stock apk. The stock
                // odex: HMG-API's backup, else the live one, a staged copy, or a NEMESIS backup
                // (lpatch only builds from the exact stock bytes, so trying each is safe)
                String odexPath = t.path.replaceAll("\\.apk$", ".odex");
                List<File> odexes = new ArrayList<File>();
                if (owned != null) odexes.add(backup(odexPath));
                else {
                    odexes.add(sys(odexPath));
                    odexes.add(new File(stockDir, odexPath));
                    odexes.addAll(nemesisCopies(odexPath));
                }
                File dex = new File(stageDir, t.path.replace('/', '_') + ".dex");
                String why = "no stock odex found";
                t.odexSrc = null;
                for (String d : ch.srcs("delta")) {
                    for (File odex : odexes) {
                        if (!odex.isFile()) continue;
                        Run r = exec(lpatch, "delta", d, odex.getPath(), dex.getPath());
                        if (r.code == 0 && dex.isFile()) { t.odexSrc = odex; break; }
                        why = r.text.trim();
                    }
                    if (t.odexSrc != null) break;
                }
                if (t.odexSrc == null) {
                    problems.add(t.path + ": " + ch.mod.id + "'s app delta doesn't fit this app's stock odex: " + why);
                    return;
                }
                File next = new File(work.getPath() + ".a");
                Run r = exec(lpatch, "zipadd", work.getPath(), dex.getPath(), next.getPath());
                if (r.code != 0 || !next.isFile()) {
                    problems.add(t.path + ": can't add classes.dex to the stock apk: " + r.text.trim());
                    return;
                }
                if (!next.renameTo(work)) throw new IOException("rename " + next);
            } else {
                problems.add(t.path + ": " + ch.mod.id + ": unknown change type " + type);
                return;
            }
        }
        if (!props.isEmpty()) {
            List<String> before = work.isFile() ? lines(work) : new ArrayList<String>();
            editProps(work, props);
            String bad = propsKept(before, lines(work), props.keySet());
            if (bad != null) {
                problems.add(t.path + ": the props edit would lose other lines (" + bad + "); nothing changed");
                return;
            }
        }
        if (!lines.isEmpty()) if (!editLines(work, lines, t.path)) return;
        if (!codecs.isEmpty()) {
            if (!work.isFile()) {
                problems.add(t.path + ": codec changes need the file");
                return;
            }
            write(work, editCodecs(read(work), codecs));
        }
        if (!work.isFile()) {
            t.exists = false;
            t.result = "absent";
            return;
        }
        if (!work.renameTo(stage)) throw new IOException("rename " + work);
        t.stage = stage;
        t.exists = true;
        t.result = md5(stage);
        // a mod's expected results (patches.json "expect", per set): a rebuilt file
        // whose name is listed must come out as one of its md5s
        for (Ch ch : t.changes) {
            Set<String> want = listed(ch.mod, "expect", t.path);
            if (!want.isEmpty() && !want.contains(t.result)) {
                problems.add(t.path + ": " + ch.mod.id + " built " + t.result + ", not the expected result " + want + "; nothing changed");
                t.result = null;
                return;
            }
        }
    }

    /** md5s that a mod's per-set list ("stock" or "expect" in patches.json) gives this file's name. */
    Set<String> listed(Mod m, String key, String path) throws IOException {
        Set<String> s = new LinkedHashSet<String>();
        Object sf = m.patches.get(key);
        if (sf == null) return s;
        File f = new File(m.dir, String.valueOf(sf).replace("{set}", m.set));
        if (!f.isFile()) return s;
        String name = new File(path).getName();
        for (String l : read(f).split("\n")) {
            String[] w = l.trim().split("\\s+");
            if (w.length >= 2 && w[0].equals(name)) s.add(w[1]);
        }
        return s;
    }

    void mergeKey(Map<String, String[]> props, String path, String key, String value, String mod) {
        String[] prev = props.get(key);
        if (prev != null && !eq(prev[0], value) && !prev[1].equals(mod)) {
            problems.add("conflict: " + path + ": " + key + ": " + prev[1] + " wants " + prev[0] + ", " + mod + " wants " + value);
        }
        props.put(key, new String[] { value, mod });
    }

    static final String CODEC = "[A-Za-z0-9_.-]{1,100}";

    /** media_codecs.xml by codec name: two mods may add or remove the same one; one adding what another removes is a conflict */
    void mergeCodec(Map<String, Object[]> codecs, String path, String name, String[] add, String mod) {
        if (!name.matches(CODEC)) {
            problems.add(path + ": " + mod + ": codec name \"" + name + "\"");
            return;
        }
        Object[] prev = codecs.get(name);
        if (prev != null && (prev[0] == null) != (add == null)) {
            problems.add("conflict: " + path + ": codec " + name + ": " + prev[1] + " " + (prev[0] == null ? "removes" : "adds")
                    + " it, " + mod + " " + (add == null ? "removes" : "adds") + " it");
        }
        if (prev != null && add != null && prev[0] != null && !java.util.Arrays.equals((String[]) prev[0], add)) {
            problems.add("conflict: " + path + ": codec " + name + ": " + prev[1] + " and " + mod + " add it differently");
        }
        codecs.put(name, new Object[] { add, mod });
    }

    /**
     * Removes the <MediaCodec> elements named (whole element, with its Quirks and
     * Types), adds new ones last in <Decoders>/<Encoders> (lowest priority) unless
     * one is there. Comments, and entries inside them, are never touched.
     */
    static String editCodecs(String xml, Map<String, Object[]> codecs) {
        StringBuilder out = new StringBuilder();
        java.util.Set<String> present = new LinkedHashSet<String>();
        int i = 0;
        while (i < xml.length()) {
            int c = xml.indexOf("<!--", i), m = xml.indexOf("<MediaCodec ", i);
            if (m < 0 && c < 0) { out.append(xml.substring(i)); break; }
            if (c >= 0 && (m < 0 || c < m)) {   // a comment: kept as is
                int e = xml.indexOf("-->", c);
                e = e < 0 ? xml.length() : e + 3;
                out.append(xml, i, e);
                i = e;
                continue;
            }
            int tagEnd = xml.indexOf('>', m);
            if (tagEnd < 0) { out.append(xml.substring(i)); break; }
            String tag = xml.substring(m, tagEnd + 1);
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(tag);
            String name = nm.find() ? nm.group(1) : "";
            int end = tag.endsWith("/>") ? tagEnd + 1 : xml.indexOf("</MediaCodec>", tagEnd) + "</MediaCodec>".length();
            if (end < tagEnd) end = tagEnd + 1;
            Object[] op = codecs.get(name);
            if (op != null && op[0] == null) {   // removed: with its line's indentation and line end
                int ls = m;
                while (ls > i && (xml.charAt(ls - 1) == ' ' || xml.charAt(ls - 1) == '\t')) ls--;
                out.append(xml, i, ls);
                int le = end;
                if (le < xml.length() && xml.charAt(le) == '\r') le++;
                if (le < xml.length() && xml.charAt(le) == '\n') le++;
                i = le;
                continue;
            }
            present.add(name);
            out.append(xml, i, end);
            i = end;
        }
        String s = out.toString();
        for (String kind : new String[] { "decoder", "encoder" }) {
            String close = "decoder".equals(kind) ? "</Decoders>" : "</Encoders>";
            StringBuilder add = new StringBuilder();
            for (Map.Entry<String, Object[]> e : codecs.entrySet()) {
                String[] a = (String[]) e.getValue()[0];
                if (a == null || !kind.equals(a[0]) || present.contains(e.getKey())) continue;
                add.append("        <MediaCodec name=\"").append(e.getKey()).append("\" type=\"").append(a[1]).append("\" />\n");
            }
            if (add.length() == 0) continue;
            int at = s.lastIndexOf(close);
            if (at < 0) continue;
            int ls = at;
            while (ls > 0 && (s.charAt(ls - 1) == ' ' || s.charAt(ls - 1) == '\t')) ls--;
            s = s.substring(0, ls) + add + s.substring(ls);
        }
        return s;
    }

    void mergeLine(Map<String, String[]> lines, String path, String line, String op, String mod) {
        String[] prev = lines.get(line);
        if (prev != null && !prev[0].equals(op) && !prev[1].equals(mod)) {
            problems.add("conflict: " + path + ": line \"" + line + "\": " + prev[1] + " wants " + prev[0] + ", " + mod + " wants " + op);
        }
        lines.put(line, new String[] { op, mod });
    }

    static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    void editProps(File f, Map<String, String[]> props) throws IOException {
        List<String> ls = f.isFile() ? new ArrayList<String>(java.util.Arrays.asList(read(f).split("\n", -1))) : new ArrayList<String>();
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        Set<String> done = new LinkedHashSet<String>();
        List<String> outl = new ArrayList<String>();
        for (String l : ls) {
            int eq = l.indexOf('=');
            String k = eq > 0 && !l.trim().startsWith("#") ? l.substring(0, eq).trim() : null;
            if (k != null && props.containsKey(k)) {
                if (done.add(k) && props.get(k)[0] != null) outl.add(k + "=" + props.get(k)[0]);
                continue;   // replaced (first) or removed / duplicate
            }
            outl.add(l);
        }
        for (Map.Entry<String, String[]> e : props.entrySet()) {
            if (!done.contains(e.getKey()) && e.getValue()[0] != null) outl.add(e.getKey() + "=" + e.getValue()[0]);
        }
        write(f, join(outl));
    }

    List<String> lines(File f) throws IOException {
        List<String> ls = new ArrayList<String>(java.util.Arrays.asList(read(f).split("\n", -1)));
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        return ls;
    }

    /**
     * After a props edit: every line that isn't one of the edited keys is still
     * there, in order, and nothing else changed. null if so, else what's wrong.
     */
    static String propsKept(List<String> before, List<String> after, Set<String> keys) {
        List<String> a = new ArrayList<String>(), b = new ArrayList<String>();
        for (String l : before) if (!isKey(l, keys)) a.add(l);
        for (String l : after) if (!isKey(l, keys)) b.add(l);
        if (!a.equals(b)) return a.size() + " other lines before, " + b.size() + " after";
        int edited = 0;
        for (String l : after) if (isKey(l, keys)) edited++;
        if (edited > keys.size()) return "a key appears twice";
        return null;
    }

    static boolean isKey(String l, Set<String> keys) {
        int eq = l.indexOf('=');
        return eq > 0 && !l.trim().startsWith("#") && keys.contains(l.substring(0, eq).trim());
    }

    boolean editLines(File f, Map<String, String[]> ops, String path) throws IOException {
        List<String> ls = f.isFile() ? new ArrayList<String>(java.util.Arrays.asList(read(f).split("\n", -1))) : new ArrayList<String>();
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        List<String> outl = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String l : ls) {
            String bare = l.trim();
            String plain = bare.startsWith("#") ? bare.substring(1).trim() : bare;
            String[] op = ops.get(plain);
            if (op == null) { outl.add(l); continue; }
            seen.add(plain);
            if ("comment".equals(op[0])) outl.add(bare.startsWith("#") ? l : "#" + l);
            else if ("uncomment".equals(op[0])) outl.add(plain);
            else if ("add".equals(op[0])) outl.add(l);
            // remove: drop it
        }
        for (Map.Entry<String, String[]> e : ops.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            if ("add".equals(e.getValue()[0])) outl.add(e.getKey());
            else if ("comment".equals(e.getValue()[0]) || "uncomment".equals(e.getValue()[0])) {
                out.add("note     " + path + ": line \"" + e.getKey() + "\" isn't there; nothing to " + e.getValue()[0] + " ["
                        + e.getValue()[1] + "]");
            }
        }
        write(f, join(outl));
        return true;
    }

    static String join(List<String> ls) {
        StringBuilder b = new StringBuilder();
        for (String l : ls) b.append(l).append('\n');
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    boolean overrideAccepts(String path, String md5) {
        Object o = hmgapi.get("overrides");
        if (!(o instanceof Map)) return false;
        Object list = ((Map<String, Object>) o).get("accept_baseline");
        if (!(list instanceof List)) return false;
        for (Object x : (List<Object>) list) {
            Map<String, Object> a = (Map<String, Object>) x;
            if (path.equals(a.get("path")) && md5.equals(a.get("md5"))) {
                out.add("override " + path + ": accepting " + md5 + " as stock (hmgapi.json overrides.accept_baseline)");
                return true;
            }
        }
        return false;
    }

    /** {set}/stock lines "<file name> <md5>" that name this path's file. */
    Set<String> setStock(Mod m, String path) throws IOException {
        Set<String> s = new LinkedHashSet<String>();
        Object sf = m.patches.get("stock");
        File f = new File(m.dir, sf == null ? "patches/{set}/stock".replace("{set}", m.set) : String.valueOf(sf).replace("{set}", m.set));
        if (!f.isFile()) return s;
        String name = new File(path).getName();
        for (String l : read(f).split("\n")) {
            String[] w = l.trim().split("\\s+");
            if (w.length >= 2 && w[0].equals(name)) s.add(w[1]);
        }
        return s;
    }

    /** Every file with this name in NEMESIS's backups (lpatch picks the one that's stock). */
    List<File> nemesisCopies(String path) {
        List<File> out = new ArrayList<File>();
        File[] pkgs = new File(root + "/data/nemesis/backup").listFiles();
        if (pkgs == null) return out;
        String name = new File(path).getName();
        for (File pkg : pkgs) {
            File f = new File(pkg, name);
            if (f.isFile()) out.add(f);
        }
        return out;
    }

    File nemesisStock(String path, Set<String> accepted) {
        File[] pkgs = new File(root + "/data/nemesis/backup").listFiles();
        if (pkgs == null || accepted.isEmpty()) return null;
        for (File pkg : pkgs) {
            File hit = find(pkg, path, accepted, 4);
            if (hit != null) return hit;
        }
        return null;
    }

    File find(File dir, String path, Set<String> accepted, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth < 0) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                File h = find(f, path, accepted, depth - 1);
                if (h != null) return h;
            } else if (!f.getName().endsWith(".absent") && backupName(f.getName(), path)) {
                try {
                    if (accepted.contains(md5(f))) return f;
                } catch (IOException e) {
                    // unreadable: skip
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ apply

    @SuppressWarnings("unchecked")
    void apply(Plan p, boolean trial) throws Exception {
        Map<String, Object> files = (Map<String, Object>) p.state.get("files");
        Map<String, Object> st = (Map<String, Object>) p.state.get("settings");
        List<Target> changed = new ArrayList<Target>();
        for (Target t : p.targets.values()) if (t.restore || !t.result.equals(p.owned(t.path))) changed.add(t);

        // journal: what each file was before, so a lost apply or a trial can be rolled back
        rm(prevDir);
        prevDir.mkdirs();
        List<Object> entries = new ArrayList<Object>();
        for (Target t : changed) {
            for (String path : withOdex(t)) {
                Map<String, Object> e = new LinkedHashMap<String, Object>();
                e.put("path", path);
                File live = sys(path);
                if (live.isFile()) {
                    File prev = new File(prevDir, path.replace('/', '_'));
                    copy(live, prev);
                    e.put("prev", prev.getPath());
                    e.put("mode", mode(live));
                }
                entries.add(e);
            }
        }
        Map<String, Object> j = new LinkedHashMap<String, Object>();
        j.put("trial", trial);
        j.put("committed", false);
        j.put("entries", entries);
        j.put("state", Json.parse(Json.write(p.state)));   // a copy: p.state changes below
        write(journalFile, Json.write(j));

        // files taken as found (changed outside HMG-API): their old backups no longer say what was there
        for (String path : p.rebased.keySet()) {
            rm(backup(path));
            if (path.endsWith(".apk")) rm(backup(path.replaceAll("\\.apk$", ".odex")));
        }
        // stock backups, once
        for (Target t : changed) {
            if (!t.newBackup) continue;
            copy(t.baseline, backup(t.path));
            if (t.app) {
                String odex = t.path.replaceAll("\\.apk$", ".odex");
                if (t.odexSrc != null && t.odexSrc.isFile() && !backup(odex).isFile()) copy(t.odexSrc, backup(odex));
            }
        }

        Set<String> held = hold(changed);
        try {
            remount("rw");
            for (Target t : changed) swap(t);
        } finally {
            remount("ro");
            release(held);
        }

        // settings
        for (Map.Entry<String, Object[]> e : p.settings.entrySet()) {
            String k = e.getKey();
            String v = String.valueOf(e.getValue()[0]);
            Map<String, Object> s = (Map<String, Object>) st.get(k);
            if (s != null && v.equals(String.valueOf(s.get("value")))) continue;
            String was;
            if (s == null) {
                s = new LinkedHashMap<String, Object>();
                s.put("stock", settingGet(k));
                st.put(k, s);
                was = s.get("stock") == null ? "" : String.valueOf(s.get("stock"));
            } else {
                was = String.valueOf(s.get("value"));
            }
            boolean live = !v.equals(was);   // mirror/restart only when the value really changes
            // already the wanted value: only take it over. Setting it again isn't harmless:
            // init re-runs its triggers (persist.sys.usb.config re-enumerated USB and dropped adb)
            if (live) settingPut(k, v);
            s.put("value", v);
            s.put("mod", e.getValue()[1]);
            Map<String, Object> c = (Map<String, Object>) e.getValue()[2];
            if (c != null && c.get("mirror") != null) s.put("mirror", c.get("mirror"));
            else s.remove("mirror");
            if (c != null && c.get("restart") != null) s.put("restart", c.get("restart"));
            else s.remove("restart");
            if (live) after(s, v);
            out.add("set      " + k + " = " + (v.isEmpty() ? "(unset)" : v));
        }
        for (String k : p.settingsRestore) {
            Map<String, Object> s = (Map<String, Object>) st.remove(k);
            settingPut(k, s.get("stock") == null ? null : String.valueOf(s.get("stock")));
            String back = s.get("stock") == null ? "" : String.valueOf(s.get("stock"));
            if (!back.equals(String.valueOf(s.get("value")))) after(s, back);
            out.add("set      " + k + " back to " + s.get("stock"));
        }

        // binds: hmgapid does them after the output is printed (restarting a service like adbd
        // may cut the link that asked); a new list clears earlier failures
        String bt = bindsText(p.binds);
        if (!bt.equals(currentBinds())) {
            write(new File(stateDir, "binds.txt"), bt);
            new File(stateDir, "binds.failed").delete();
            bindsChanged = true;
        }

        // state
        for (Target t : changed) {
            if (t.restore) {
                files.remove(t.path);
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("stock", t.stock);
            f.put("result", t.result);
            f.put("mods", new ArrayList<Object>(t.mods));
            if (t.app) f.put("app", true);
            files.put(t.path, f);
        }
        write(stateFile, Json.write(p.state));
        if (trial) {
            j.put("committed", true);
            write(journalFile, Json.write(j));
            out.add("trial: undone at the next start unless you run `engine keep`");
        } else {
            journalFile.delete();
            rm(prevDir);
        }
        rm(stageDir);
        Set<String> needs = new LinkedHashSet<String>();
        for (Target t : changed) needs.add(restart(t.path));
        if (!p.settings.isEmpty() || !p.settingsRestore.isEmpty()) needs.add("settings applied now");
        out.add("done: " + changed.size() + " file(s); " + needs);
    }

    List<String> withOdex(Target t) {
        List<String> l = new ArrayList<String>();
        l.add(t.path);
        if (t.app) l.add(t.path.replaceAll("\\.apk$", ".odex"));
        return l;
    }

    void swap(Target t) throws Exception {
        File dst = sys(t.path);
        if (t.restore) {
            if ("absent".equals(t.stock)) {
                if (dst.exists() && !dst.delete()) throw new IOException("delete " + dst);
            } else {
                put(backup(t.path), dst, "644");
            }
            if (t.app) {
                String odex = t.path.replaceAll("\\.apk$", ".odex");
                if (backup(odex).isFile()) put(backup(odex), sys(odex), "644");
                dalvik(t.path, sys(odex), false);
            }
            out.add("restored " + t.path);
            return;
        }
        if (!t.exists) {
            if (dst.exists() && !dst.delete()) throw new IOException("delete " + dst);
            return;
        }
        put(t.stage, dst, t.changes.isEmpty() || t.changes.get(0).c.get("mode") == null ? "644"
                : String.valueOf(t.changes.get(0).c.get("mode")));
        if (t.app) {
            File odex = sys(t.path.replaceAll("\\.apk$", ".odex"));
            odex.delete();
            dalvik(t.path, odex, true);
        }
        out.add("wrote    " + t.path);
    }

    /** dexopt a rebuilt app and fill its Dalvik cache entry, as the 1.x package did. */
    void dalvik(String apkPath, File odex, boolean dexopt) throws Exception {
        if (host) return;
        String apk = new File(apkPath).getName();
        exec("/system/bin/sh", "-c", "rm -f /data/dalvik-cache/*" + apk + "* /cache/dalvik-cache/*" + apk + "*");
        if (dexopt) {
            Run r = exec("/system/bin/dexopt", "--preopt", sys(apkPath).getPath(), odex.getPath(), "m=y,u=n");
            if (r.code != 0 || !odex.isFile()) throw new IOException("dexopt " + apkPath + ": " + r.text);
            exec("/system/bin/chmod", "644", odex.getPath());
            exec("/system/bin/chown", "1000:1000", odex.getPath());
        }
        if (odex.isFile()) {
            File cache = new File("/data/dalvik-cache/system@app@" + apk + "@classes.dex");
            File tmp = new File(cache.getPath() + ".tmp");
            copy(odex, tmp);
            exec("/system/bin/chmod", "644", tmp.getPath());
            exec("/system/bin/chown", "1000:1000", tmp.getPath());
            if (!tmp.renameTo(cache)) tmp.delete();
        }
    }

    /** Write next to the target and rename, so a reader never sees half a file. */
    void put(File src, File dst, String mode) throws Exception {
        File tmp = new File(dst.getPath() + ".hmgapi-new");
        dst.getParentFile().mkdirs();
        copy(src, tmp);
        if (!host) {
            exec("/system/bin/chmod", mode, tmp.getPath());
            exec("/system/bin/chown", "0:0", tmp.getPath());
        }
        if (!md5(tmp).equals(md5(src))) {
            tmp.delete();
            throw new IOException("copy to " + dst + " came out different");
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("rename onto " + dst);
        }
    }

    Set<String> hold(List<Target> changed) throws Exception {
        Set<String> held = new LinkedHashSet<String>();
        if (host || !"1".equals(getprop("sys.boot_completed"))) return held;
        Map<String, String> pkgs = new LinkedHashMap<String, String>();
        for (String l : exec("/system/bin/pm", "list", "packages", "-f").text.split("\n")) {
            l = l.trim();
            int eq = l.lastIndexOf('=');
            if (l.startsWith("package:") && eq > 0) pkgs.put(l.substring(8, eq), l.substring(eq + 1));
        }
        for (Target t : changed) {
            String pkg = pkgs.get(t.path);
            if (pkg != null && exec("/system/bin/pm", "disable", pkg).code == 0) held.add(pkg);
        }
        if (!held.isEmpty()) out.add("stopped  " + held + " while their files change");
        return held;
    }

    void release(Set<String> held) throws Exception {
        for (String pkg : held) exec("/system/bin/pm", "enable", pkg);
    }

    void remount(String mode) throws Exception {
        if (host) return;
        Run r = exec(env("HMGAPI_HMGAPID", "/system/bin/hmgapid"), "remount", mode);
        if (r.code != 0 && "rw".equals(mode)) throw new IOException("remount rw: " + r.text);
    }

    // ------------------------------------------------------------------ other commands

    @SuppressWarnings("unchecked")
    int status() throws Exception {
        Map<String, Object> state = loadState();
        Map<String, Object> files = (Map<String, Object>) state.get("files");
        if (files.isEmpty() && ((Map<String, Object>) state.get("settings")).isEmpty()) out.add("HMG-API owns no files");
        int bad = 0;
        for (Map.Entry<String, Object> e : files.entrySet()) {
            Map<String, Object> f = (Map<String, Object>) e.getValue();
            String live = md5(sys(e.getKey()));
            boolean ok = live.equals(f.get("result"));
            if (!ok) bad++;
            out.add((ok ? "ok       " : "CHANGED  ") + e.getKey() + " " + f.get("mods") + " stock " + f.get("stock") + ", expected "
                    + f.get("result") + (ok ? "" : ", found " + live));
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) state.get("settings")).entrySet()) {
            Map<String, Object> s = (Map<String, Object>) e.getValue();
            out.add("setting  " + e.getKey() + " = " + s.get("value") + " [" + s.get("mod") + "], stock " + s.get("stock"));
        }
        Set<String> on = new LinkedHashSet<String>();
        adbTcpWarning(LiveState.mods(this, on), on);
        if (journalFile.exists()) {
            Map<String, Object> j = Json.object(read(journalFile));
            out.add(Boolean.TRUE.equals(j.get("trial")) ? "trial apply in place: `engine keep` keeps it, the next start undoes it"
                    : "an apply didn't finish: the next start rolls it back (`engine recover`)");
        }
        return bad == 0 ? 0 : 4;
    }

    /**
     * The car's ADB TCP switch (persist.service.adb.tcp.enable=1: Engineering Mode, or
     * NEMESIS's Enable ADB) also runs `ifconfig eth0 192.168.0.123` (init.daudio.rc),
     * in the hotspot's 192.168.0.0/24: the hotspot's DHCP offers then leave by eth0 and
     * phones get no address, so wireless CarPlay and Android Auto never start. Said
     * when a mod on wants wireless projection and the adb mod (which holds the switch
     * at 0) isn't on.
     */
    void adbTcpWarning(Map<String, Map<String, Object>> all, Set<String> on) {
        if (!"1".equals(getprop("persist.service.adb.tcp.enable")) || on.contains("adb")) return;
        for (String id : on) {
            Map<String, Object> m = all.get(id);
            Object f = m == null ? null : m.get("feature_flags");
            if (f != null && String.valueOf(f).contains("_wireless")) {
                out.add("WARNING: the car's ADB TCP switch is on (persist.service.adb.tcp.enable=1). It puts the internal eth0 on "
                        + "192.168.0.123, in the hotspot's range, so phones get no address and wireless CarPlay/Android Auto "
                        + "(" + id + ") can't connect. Turn on the adb mod: it holds that switch off and has its own adb over "
                        + "Wi-Fi switch that leaves eth0 alone (a restart clears eth0)");
                return;
            }
        }
    }

    int keep() throws Exception {
        if (!journalFile.exists()) {
            out.add("no trial to keep");
            return 0;
        }
        Map<String, Object> j = Json.object(read(journalFile));
        if (!Boolean.TRUE.equals(j.get("trial")) || !Boolean.TRUE.equals(j.get("committed"))) {
            out.add("the last apply didn't finish; it can only be rolled back (`engine recover`)");
            return 1;
        }
        journalFile.delete();
        rm(prevDir);
        out.add("kept");
        return 0;
    }

    @SuppressWarnings("unchecked")
    int recover() throws Exception {
        if (!journalFile.exists()) {
            out.add("nothing to recover");
            return 0;
        }
        Map<String, Object> j = Json.object(read(journalFile));
        remount("rw");
        try {
            for (Object o : (List<Object>) j.get("entries")) {
                Map<String, Object> e = (Map<String, Object>) o;
                String path = (String) e.get("path");
                File dst = sys(path);
                Object prev = e.get("prev");
                if (prev == null) {
                    if (dst.exists()) dst.delete();
                } else {
                    put(new File((String) prev), dst, e.get("mode") == null ? "644" : String.valueOf(e.get("mode")));
                }
                if (path.endsWith(".odex") && !host) dalvik(path.replaceAll("\\.odex$", ".apk"), dst, false);
                out.add("rolled back " + path);
            }
        } finally {
            remount("ro");
        }
        write(stateFile, Json.write(j.get("state")));
        journalFile.delete();
        rm(prevDir);
        out.add(Boolean.TRUE.equals(j.get("trial")) ? "trial undone" : "unfinished apply rolled back");
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    Map<String, Object> loadState() throws IOException {
        Map<String, Object> s = stateFile.isFile() ? Json.object(read(stateFile)) : new LinkedHashMap<String, Object>();
        if (!(s.get("files") instanceof Map)) s.put("files", new LinkedHashMap<String, Object>());
        if (!(s.get("settings") instanceof Map)) s.put("settings", new LinkedHashMap<String, Object>());
        return s;
    }

    File sys(String path) { return new File(root + path); }

    File backup(String path) { return new File(stockDir, path); }

    String mode(File f) { return "644"; }

    String settingGet(String k) throws Exception {
        if (host) return hostSettings().get(k);
        if (k.startsWith("prop/")) return getprop(k.substring(5));
        String[] nk = k.split("/", 2);
        String v = exec("/system/bin/settings", "get", nk[0], nk[1]).text.trim();
        return "null".equals(v) ? null : v;
    }

    void settingPut(String k, String v) throws Exception {
        if (host) {
            Map<String, String> m = hostSettings();
            if (v == null || (k.startsWith("prop/") && v.isEmpty())) m.remove(k);
            else m.put(k, v);
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            write(new File(root + "/settings.txt"), b.toString());
            File log = new File(root + "/settings.log");   // every write, for tests
            write(log, (log.isFile() ? read(log) : "") + k + "=" + (v == null ? "" : v) + "\n");
            return;
        }
        if (k.startsWith("prop/")) {
            String key = k.substring(5);
            if (v == null || v.isEmpty()) {
                exec("/system/bin/setprop", key, "");   // live value gone now (this writes an empty saved value...)
                // unset before HMG-API: drop the saved value (an empty setprop would save ""); stock again at the next start
                new File("/data/property/" + key).delete();   // ...which goes too: unset again
            } else {
                exec("/system/bin/setprop", key, v);
            }
            return;
        }
        String[] nk = k.split("/", 2);
        if (v == null) exec("/system/bin/settings", "delete", nk[0], nk[1]);
        else exec("/system/bin/settings", "put", nk[0], nk[1], v);
    }

    Map<String, String> hostSettings() throws IOException {
        Map<String, String> m = new TreeMap<String, String>();
        File f = new File(root + "/settings.txt");
        if (f.isFile()) for (String l : read(f).split("\n")) if (l.indexOf('=') > 0) m.put(l.substring(0, l.indexOf('=')), l.substring(l.indexOf('=') + 1));
        return m;
    }

    String getprop(String k) {
        if (host) {   // tests: HMGAPI_PROPS="key=value key=value"
            for (String kv : env("HMGAPI_PROPS", "").split(" ")) if (kv.startsWith(k + "=")) return kv.substring(k.length() + 1);
            return null;
        }
        try {
            String v = exec("/system/bin/getprop", k).text.trim();
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    static final class Run {
        int code;
        String text;
    }

    static Run exec(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        Run r = new Run();
        r.text = new String(readAll(pr.getInputStream()), "UTF-8");
        r.code = pr.waitFor();
        return r;
    }

    static String md5(File f) throws IOException {
        if (!f.isFile()) return "absent";
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            InputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) d.update(b, 0, n);
            } finally {
                in.close();
            }
            StringBuilder s = new StringBuilder();
            for (byte x : d.digest()) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    public static String read(File f) throws IOException { return new String(readAll(new FileInputStream(f)), "UTF-8"); }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream o = new FileOutputStream(tmp);
        try {
            o.write(s.getBytes("UTF-8"));
            ((FileOutputStream) o).getFD().sync();
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + tmp);
    }

    static void copy(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        InputStream in = new FileInputStream(src);
        FileOutputStream o = new FileOutputStream(dst);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.getFD().sync();
        } finally {
            in.close();
            o.close();
        }
    }

    /** Mod files: root only (the NEMESIS daemon's umask 0 would leave them world-writable). */
    void chmodTree(File f) {
        if (host) return;
        try {
            exec("/system/bin/chmod", "-R", "700", f.getPath());
        } catch (Exception ignored) {
            // best effort: hmgapid keeps /data/hmgapi itself 0700
        }
    }

    static void rm(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) rm(c);
        f.delete();
    }
}
