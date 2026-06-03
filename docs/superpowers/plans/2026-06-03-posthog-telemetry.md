# PostHog Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add anonymous, always-on, fail-safe PostHog telemetry to FlashbackTurbo so we can drive improvements from real usage of the export pipeline and each hook (H4/H8/H9/H10).

**Architecture:** A static `Telemetry` facade owns a shaded `posthog-java` client (relocated to avoid mod classloader conflicts), an anonymous persisted UUID, device super-properties, and a sanitizer. The existing Mixins call `Telemetry.capture(...)` at verified injection points. Every public telemetry method is try/catch fail-safe — telemetry must NEVER break an export.

**Tech Stack:** Java 21, Fabric Loom 1.16.2, Gradle Shadow plugin (`com.gradleup.shadow`), `com.posthog.java:posthog`, Gson (already present), Yarn mappings, JUnit 5 (new test source set).

---

## File Structure

**New files:**
- `src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java` — static facade: `init()`, `capture()`, `shutdown()`.
- `src/main/java/fr/zeffut/flashbackturbo/telemetry/AnonymousId.java` — persisted random UUID (distinct_id).
- `src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java` — one-shot system/version super-properties.
- `src/main/java/fr/zeffut/flashbackturbo/telemetry/Sanitizer.java` — strips paths/PII, reduces stacktraces.
- `src/main/java/fr/zeffut/flashbackturbo/telemetry/ExportContext.java` — accumulates per-export properties + timing across injection points.
- `src/test/java/fr/zeffut/flashbackturbo/telemetry/AnonymousIdTest.java`
- `src/test/java/fr/zeffut/flashbackturbo/telemetry/SanitizerTest.java`
- `src/test/java/fr/zeffut/flashbackturbo/telemetry/TelemetryTest.java`
- `src/test/java/fr/zeffut/flashbackturbo/telemetry/ExportContextTest.java`

**Modified files:**
- `build.gradle` — Shadow plugin, posthog dependency, relocation, Loom×Shadow wiring, test deps.
- `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java` — `enableTelemetry` flag.
- `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java` — `Telemetry.init()` + log line.
- `src/main/java/fr/zeffut/flashbackturbo/mixin/exporting/ExportJobMixin.java` — export start/recovered events.
- `src/main/java/fr/zeffut/flashbackturbo/mixin/gui/AsyncFFmpegFinishMixin.java` — overlay/finish events.
- `src/main/java/fr/zeffut/flashbackturbo/mixin/encoder/AsyncFFmpegVideoWriterMixin.java` — cap-lifted/resolution event.

---

## Task 1: Add JUnit test infrastructure

No test source set exists yet. The pure-logic telemetry units (`AnonymousId`, `Sanitizer`, `ExportContext`, `Telemetry` no-op behavior) are testable without Minecraft.

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add the test dependencies and JUnit platform to `build.gradle`**

In the `dependencies { ... }` block, append:

```gradle
    testImplementation "org.junit.jupiter:junit-jupiter:5.10.2"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher:1.10.2"
```

After the `java { ... }` block, add:

```gradle
test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Create a trivial sanity test**

Create `src/test/java/fr/zeffut/flashbackturbo/telemetry/SanityTest.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SanityTest {
    @Test
    void junitRuns() {
        assertEquals(4, 2 + 2);
    }
}
```

- [ ] **Step 3: Run it**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.SanityTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 4: Delete the sanity test**

Run: `rm src/test/java/fr/zeffut/flashbackturbo/telemetry/SanityTest.java`

- [ ] **Step 5: Commit**

```bash
git add build.gradle
git commit -m "build(test): add JUnit 5 test source set"
```

---

## Task 2: `AnonymousId` — persisted random distinct_id (TDD)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/telemetry/AnonymousId.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/telemetry/AnonymousIdTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/fr/zeffut/flashbackturbo/telemetry/AnonymousIdTest.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnonymousIdTest {

    @Test
    void createsAndPersistsId(@TempDir Path dir) {
        Path file = dir.resolve("flashbackturbo_telemetry.json");
        String first = AnonymousId.loadOrCreate(file);
        assertDoesNotThrow(() -> UUID.fromString(first));
        assertTrue(Files.exists(file));
    }

    @Test
    void returnsSameIdOnSecondCall(@TempDir Path dir) {
        Path file = dir.resolve("flashbackturbo_telemetry.json");
        String first = AnonymousId.loadOrCreate(file);
        String second = AnonymousId.loadOrCreate(file);
        assertEquals(first, second);
    }

    @Test
    void regeneratesOnCorruptFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("flashbackturbo_telemetry.json");
        Files.writeString(file, "not json at all {{{");
        String id = AnonymousId.loadOrCreate(file);
        assertDoesNotThrow(() -> UUID.fromString(id));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.AnonymousIdTest"`
Expected: FAIL — `AnonymousId` does not exist / cannot find symbol.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/fr/zeffut/flashbackturbo/telemetry/AnonymousId.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * UUID anonyme persisté, utilisé comme distinct_id PostHog. Aucune PII : c'est un identifiant
 * aléatoire stable par installation. Stocké dans un fichier séparé de la config principale pour
 * survivre à un reset de flashbackturbo.json.
 */
public final class AnonymousId {

    private static final Gson GSON = new Gson();

    private AnonymousId() {}

    /** Lit l'UUID depuis {@code file} ; en crée un aléatoire (et l'écrit) si absent/corrompu. */
    public static String loadOrCreate(Path file) {
        String existing = tryRead(file);
        if (existing != null) return existing;

        String id = UUID.randomUUID().toString();
        tryWrite(file, id);
        return id;
    }

    private static String tryRead(Path file) {
        try {
            if (!Files.exists(file)) return null;
            JsonObject obj = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (obj == null || !obj.has("anonymous_id")) return null;
            String id = obj.get("anonymous_id").getAsString();
            UUID.fromString(id); // valide le format
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private static void tryWrite(Path file, String id) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("anonymous_id", id);
            Files.writeString(file, GSON.toJson(obj));
        } catch (Exception e) {
            // best-effort : si on ne peut pas écrire, l'id sera régénéré au prochain lancement
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.AnonymousIdTest"`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/AnonymousId.java src/test/java/fr/zeffut/flashbackturbo/telemetry/AnonymousIdTest.java
git commit -m "feat(telemetry): persisted anonymous distinct_id"
```

---

## Task 3: `Sanitizer` — strip PII and reduce stacktraces (TDD)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/telemetry/Sanitizer.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/telemetry/SanitizerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/fr/zeffut/flashbackturbo/telemetry/SanitizerTest.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SanitizerTest {

    @Test
    void stripsUnixHomePaths() {
        String in = "failed to open /Users/alice/replays/my world.mp4";
        String out = Sanitizer.sanitizeMessage(in);
        assertFalse(out.contains("alice"));
        assertFalse(out.contains("my world"));
        assertTrue(out.contains("<path>"));
    }

    @Test
    void stripsWindowsPaths() {
        String in = "C:\\Users\\Bob\\AppData\\replay.mkv missing";
        String out = Sanitizer.sanitizeMessage(in);
        assertFalse(out.contains("Bob"));
        assertTrue(out.contains("<path>"));
    }

    @Test
    void stripsIpAddresses() {
        String in = "connection lost to 192.168.1.42:25565";
        String out = Sanitizer.sanitizeMessage(in);
        assertFalse(out.contains("192.168.1.42"));
        assertTrue(out.contains("<ip>"));
    }

    @Test
    void nullMessageBecomesEmpty() {
        assertEquals("", Sanitizer.sanitizeMessage(null));
    }

    @Test
    void topFramesReturnsClassAndMethodOnly() {
        Throwable t = new RuntimeException("boom");
        String frames = Sanitizer.topFrames(t, 3);
        assertTrue(frames.contains("SanitizerTest"));
        assertFalse(frames.contains(".java:")); // pas de fichier:ligne local
    }

    @Test
    void topFramesHandlesNull() {
        assertEquals("", Sanitizer.topFrames(null, 3));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.SanitizerTest"`
Expected: FAIL — `Sanitizer` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/fr/zeffut/flashbackturbo/telemetry/Sanitizer.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import java.util.regex.Pattern;

/**
 * Nettoie tout texte sortant de PII avant envoi à PostHog : chemins de fichiers (qui contiennent
 * pseudo OS / noms de monde), adresses IP. Réduit aussi les stacktraces aux classes+méthodes
 * (jamais de chemin de fichier local).
 */
public final class Sanitizer {

    // Chemins Unix absolus (/a/b/c...) jusqu'au prochain blanc, et chemins Windows (C:\a\b...).
    private static final Pattern UNIX_PATH = Pattern.compile("/(?:[^/\\s]+/)+[^/\\s]*");
    private static final Pattern WIN_PATH = Pattern.compile("[A-Za-z]:\\\\(?:[^\\\\\\s]+\\\\?)+");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    private Sanitizer() {}

    /** Remplace chemins et IPs par des placeholders. Null → chaîne vide. */
    public static String sanitizeMessage(String msg) {
        if (msg == null) return "";
        String out = WIN_PATH.matcher(msg).replaceAll("<path>");
        out = UNIX_PATH.matcher(out).replaceAll("<path>");
        out = IP.matcher(out).replaceAll("<ip>");
        return out;
    }

    /** Les n premières frames d'une stacktrace, format "Class.method" par ligne, sans fichier:ligne. */
    public static String topFrames(Throwable t, int n) {
        if (t == null) return "";
        StackTraceElement[] st = t.getStackTrace();
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(n, st.length);
        for (int i = 0; i < limit; i++) {
            StackTraceElement e = st[i];
            String simpleClass = e.getClassName();
            int dot = simpleClass.lastIndexOf('.');
            if (dot >= 0) simpleClass = simpleClass.substring(dot + 1);
            if (i > 0) sb.append(" <- ");
            sb.append(simpleClass).append('.').append(e.getMethodName());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.SanitizerTest"`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/Sanitizer.java src/test/java/fr/zeffut/flashbackturbo/telemetry/SanitizerTest.java
git commit -m "feat(telemetry): PII sanitizer for outgoing payloads"
```

---

## Task 4: `ExportContext` — accumulate per-export properties + timing (TDD)

This holds export properties that become known at different injection points (start time, resolution, encoder, format, frames) and produces the merged property map for `fbt_export_finished` / `fbt_export_failed`. Pure logic, fully testable.

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/telemetry/ExportContext.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/telemetry/ExportContextTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/fr/zeffut/flashbackturbo/telemetry/ExportContextTest.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExportContextTest {

    @Test
    void beginSetsStartedFlagAndProps() {
        ExportContext ctx = new ExportContext();
        ctx.begin(1_000_000L);
        ctx.put("format", "mp4");
        assertTrue(ctx.isActive());
        Map<String, Object> p = ctx.snapshot(2_000_000L);
        assertEquals("mp4", p.get("format"));
        assertEquals(1L, p.get("duration_ms")); // (2_000_000 - 1_000_000) ns = 1 ms
    }

    @Test
    void snapshotComputesDurationFromStart() {
        ExportContext ctx = new ExportContext();
        ctx.begin(0L);
        Map<String, Object> p = ctx.snapshot(5_000_000L);
        assertEquals(5L, p.get("duration_ms"));
    }

    @Test
    void endClearsActive() {
        ExportContext ctx = new ExportContext();
        ctx.begin(0L);
        ctx.end();
        assertFalse(ctx.isActive());
    }

    @Test
    void snapshotWhenInactiveHasNoDuration() {
        ExportContext ctx = new ExportContext();
        Map<String, Object> p = ctx.snapshot(123L);
        assertFalse(p.containsKey("duration_ms"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.ExportContextTest"`
Expected: FAIL — `ExportContext` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/fr/zeffut/flashbackturbo/telemetry/ExportContext.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumule les propriétés d'un export en cours, renseignées au fil des points d'injection
 * (résolution, encoder, format, nb frames…) + le timestamp de début, et produit la map de
 * propriétés pour les events de fin. Un seul export à la fois côté client Flashback, donc une
 * instance statique partagée suffit (voir {@link Telemetry}). Thread-safety : toutes les
 * méthodes sont synchronized car start/encode/finish peuvent toucher des threads différents.
 */
public final class ExportContext {

    private final Map<String, Object> props = new LinkedHashMap<>();
    private long startNanos = 0L;
    private boolean active = false;

    public synchronized void begin(long nowNanos) {
        props.clear();
        startNanos = nowNanos;
        active = true;
    }

    public synchronized void put(String key, Object value) {
        props.put(key, value);
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void end() {
        active = false;
    }

    /** Copie des propriétés + duration_ms si un export est actif. */
    public synchronized Map<String, Object> snapshot(long nowNanos) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        if (active) {
            out.put("duration_ms", (nowNanos - startNanos) / 1_000_000L);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.ExportContextTest"`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/ExportContext.java src/test/java/fr/zeffut/flashbackturbo/telemetry/ExportContextTest.java
git commit -m "feat(telemetry): per-export property + timing accumulator"
```

---

## Task 5: Add `enableTelemetry` config flag

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java`

- [ ] **Step 1: Add the field**

In `TurboConfig.java`, after the `fixExportSetupRace` field declaration (before `private TurboConfig() {}`), add:

```java
    /**
     * Télémétrie PostHog anonyme (toujours active par défaut). Aucune donnée identifiante :
     * distinct_id = UUID aléatoire local, messages d'exception sanitisés. Mettre à {@code false}
     * désactive totalement l'envoi (aucun appel réseau, aucun fichier d'id créé).
     */
    public boolean enableTelemetry = true;
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java
git commit -m "feat(config): add enableTelemetry kill-switch flag"
```

---

## Task 6: Shade `posthog-java` into the jar (build integration — RISK POINT)

This is the highest-risk task (Loom × Shadow). Do it in isolation and verify the relocated classes land in the remapped jar.

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add the Shadow plugin**

In the `plugins { ... }` block of `build.gradle`, add after the loom line:

```gradle
    id 'com.gradleup.shadow' version '8.3.6'
```

- [ ] **Step 2: Add the dependency and a dedicated `shade` configuration**

In `dependencies { ... }`, add:

```gradle
    // Télémétrie — shadée + relocalisée (voir shadowJar plus bas) pour éviter tout conflit
    // de classloader avec un autre mod qui embarquerait posthog-java.
    implementation "com.posthog.java:posthog:1.1.1"
```

- [ ] **Step 3: Enumerate the transitive dependencies to relocate**

Run: `./gradlew dependencies --configuration runtimeClasspath | grep -iE "posthog|okhttp|okio|org.json|moshi|kotlin"`
Expected: a list including `com.posthog.java:posthog` and its transitive HTTP/JSON libs.
**Record every distinct top-level package** among them (e.g. `com.posthog`, plus whatever JSON/HTTP packages appear). You will relocate each in Step 4.

- [ ] **Step 4: Configure relocation + Loom×Shadow wiring**

At the end of `build.gradle`, add (relocate-lines: keep `com.posthog`; ADD one `relocate` line per extra top-level package found in Step 3 — e.g. `org.json`, `okhttp3`, `okio`):

```gradle
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

shadowJar {
    configurations = [project.configurations.runtimeClasspath]
    // On ne garde QUE posthog + ses deps ; pas les libs déjà fournies par Minecraft/Fabric.
    dependencies {
        include(dependency('com.posthog.java:posthog'))
        // ADD include(dependency('group:artifact')) pour chaque dep transitive listée au Step 3.
    }
    relocate 'com.posthog', 'fr.zeffut.flashbackturbo.shadow.posthog'
    // ADD une ligne relocate par package transitif (ex. relocate 'org.json', 'fr.zeffut.flashbackturbo.shadow.json').
    archiveClassifier = 'shadow'
}

// Loom remappe le jar ; on lui fait prendre la sortie du shadowJar en entrée pour que les
// classes relocalisées soient bien dans le jar final remappé.
remapJar {
    dependsOn shadowJar
    inputFile = shadowJar.archiveFile
}
```

- [ ] **Step 5: Build the jar**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If it fails on the `remapJar`/`shadowJar` interaction, see the fallback note below before retrying.

- [ ] **Step 6: Verify relocation landed in the final jar**

Run: `jar tf build/libs/flashbackturbo-*.jar | grep -E "posthog" | head`
Expected: paths under `fr/zeffut/flashbackturbo/shadow/posthog/...` — and **NO** path under bare `com/posthog/...`.
If you see bare `com/posthog/...`, relocation did not apply — fix Step 4 before continuing.

> **Fallback if Loom×Shadow cannot be reconciled:** revert this task, and instead replace the `posthog-java` SDK with a lightweight HTTP sender (java.net.http.HttpClient POST to `https://eu.i.posthog.com/batch/` with a Gson-built JSON body `{api_key, batch:[{event, distinct_id, properties}]}`, on a single daemon thread + bounded queue). This removes the shading risk entirely while keeping the exact same `Telemetry` public API in Task 7 — only the internals of `init()/capture()/shutdown()` change. Note this deviation in the commit message.

- [ ] **Step 7: Commit**

```bash
git add build.gradle
git commit -m "build(telemetry): shade + relocate posthog-java into the jar"
```

---

## Task 7: `Telemetry` facade — init/capture/shutdown, fail-safe (TDD for no-op path)

The networked path can't be unit-tested without a live endpoint, but the **fail-safe contract** (never throw; no-op when disabled or uninitialized) can and must be. We test that `capture()` never throws before `init()` and when disabled.

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java`
- Test: `src/test/java/fr/zeffut/flashbackturbo/telemetry/TelemetryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/fr/zeffut/flashbackturbo/telemetry/TelemetryTest.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TelemetryTest {

    @Test
    void captureBeforeInitDoesNotThrow() {
        assertDoesNotThrow(() -> Telemetry.capture("fbt_test", Map.of("k", "v")));
    }

    @Test
    void captureWithNullPropsDoesNotThrow() {
        assertDoesNotThrow(() -> Telemetry.capture("fbt_test", null));
    }

    @Test
    void shutdownBeforeInitDoesNotThrow() {
        assertDoesNotThrow(Telemetry::shutdown);
    }

    @Test
    void exportContextAccessorIsNeverNull() {
        assertDoesNotThrow(() -> Telemetry.export().isActive());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.TelemetryTest"`
Expected: FAIL — `Telemetry` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import com.posthog.java.PostHog;
import fr.zeffut.flashbackturbo.FlashbackTurboClient;
import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Façade statique de télémétrie PostHog. Anonyme, toujours active (sauf kill-switch config),
 * et STRICTEMENT fail-safe : aucune méthode ne propage jamais d'exception — un échec de
 * télémétrie ne doit jamais casser un export. Tout l'I/O réseau est asynchrone (géré par le SDK).
 */
public final class Telemetry {

    private static final String API_KEY = "phc_zdMj4p5wo8EvfVApjb2EbfUHJ76zgYGM5wAGz5YJC359";
    private static final String HOST = "https://eu.i.posthog.com";
    private static final String ID_FILENAME = "flashbackturbo_telemetry.json";

    private static volatile PostHog client; // null = désactivé ou init échoué → capture() no-op
    private static volatile String distinctId;
    private static volatile Map<String, Object> superProps = Map.of();
    private static final ExportContext EXPORT = new ExportContext();

    private Telemetry() {}

    /** Accès au contexte d'export partagé (jamais null). */
    public static ExportContext export() {
        return EXPORT;
    }

    /** Initialise le client. No-op total si la télémétrie est désactivée. Ne lève jamais. */
    public static void init() {
        try {
            if (!TurboConfig.current().enableTelemetry) {
                FlashbackTurboClient.LOGGER.info("[telemetry] désactivée (enableTelemetry=false)");
                return;
            }
            Path idFile = FabricLoader.getInstance().getConfigDir().resolve(ID_FILENAME);
            distinctId = AnonymousId.loadOrCreate(idFile);
            superProps = DeviceProfile.collect();
            client = new PostHog.Builder(API_KEY).host(HOST).build();
            Runtime.getRuntime().addShutdownHook(new Thread(Telemetry::shutdown, "fbt-telemetry-shutdown"));
            capture("fbt_mod_loaded", null);
            FlashbackTurboClient.LOGGER.info("[telemetry] initialisée (anonyme, host={})", HOST);
        } catch (Throwable t) {
            client = null;
            FlashbackTurboClient.LOGGER.debug("[telemetry] init échouée — télémétrie désactivée", t);
        }
    }

    /** Envoie un event. No-op si non initialisée. Ne lève jamais. */
    public static void capture(String event, Map<String, Object> props) {
        try {
            PostHog c = client;
            if (c == null || distinctId == null) return;
            Map<String, Object> merged = new HashMap<>(superProps);
            if (props != null) merged.putAll(props);
            c.capture(distinctId, event, merged);
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[telemetry] capture('{}') échouée", event, t);
        }
    }

    /** Flush + ferme le client. Idempotent. Ne lève jamais. */
    public static void shutdown() {
        try {
            PostHog c = client;
            if (c != null) c.shutdown();
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[telemetry] shutdown échouée", t);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "fr.zeffut.flashbackturbo.telemetry.TelemetryTest"`
Expected: PASS — 4 tests. (`client` stays null because `TurboConfig.current()` default has telemetry on but `init()` is never called in the test; `capture` no-ops because `client == null`.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java src/test/java/fr/zeffut/flashbackturbo/telemetry/TelemetryTest.java
git commit -m "feat(telemetry): fail-safe PostHog facade (init/capture/shutdown)"
```

---

## Task 8: `DeviceProfile` — system + version super-properties

Depends on Minecraft/Fabric runtime classes, so it is **not** unit-tested (no MC in the test classpath). Verified by build + runtime smoke test in Task 11.

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java`

- [ ] **Step 1: Write the implementation**

Create `src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java`:

```java
package fr.zeffut.flashbackturbo.telemetry;

import fr.zeffut.flashbackturbo.config.TurboConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collecte one-shot des propriétés système et de versions, attachées comme super-properties à
 * chaque event. Aucune donnée identifiante (pas de pseudo, pas de chemin). Fail-safe : toute
 * propriété qu'on ne peut pas lire est simplement omise.
 */
public final class DeviceProfile {

    private DeviceProfile() {}

    public static Map<String, Object> collect() {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            p.put("os", System.getProperty("os.name", "unknown"));
            p.put("os_version", System.getProperty("os.version", "unknown"));
            p.put("arch", System.getProperty("os.arch", "unknown"));
            p.put("java_version", System.getProperty("java.version", "unknown"));
            p.put("cpu_cores", Runtime.getRuntime().availableProcessors());
            p.put("max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));

            FabricLoader loader = FabricLoader.getInstance();
            p.put("mods_count", loader.getAllMods().size());
            modVersion(loader, "minecraft").ifPresent(v -> p.put("mc_version", v));
            modVersion(loader, "fabricloader").ifPresent(v -> p.put("fabric_loader_version", v));
            modVersion(loader, "flashback").ifPresent(v -> p.put("flashback_version", v));
            modVersion(loader, "flashbackturbo").ifPresent(v -> p.put("fbt_version", v));

            TurboConfig cfg = TurboConfig.current();
            p.put("cfg_liftResolutionCap", cfg.liftResolutionCap);
            p.put("cfg_tuneFFmpegThreading", cfg.tuneFFmpegThreading);
            p.put("cfg_parallelPngWriter", cfg.parallelPngWriter);
            p.put("cfg_pngCompressionLevel", cfg.pngCompressionLevel);
            p.put("cfg_showExportProgressOverlay", cfg.showExportProgressOverlay);
            p.put("cfg_useFragmentedMp4OnHwEncoders", cfg.useFragmentedMp4OnHwEncoders);
            p.put("cfg_fixExportSetupRace", cfg.fixExportSetupRace);
        } catch (Throwable t) {
            // best-effort : on renvoie ce qu'on a pu collecter
        }
        return p;
    }

    private static java.util.Optional<String> modVersion(FabricLoader loader, String id) {
        return loader.getModContainer(id)
            .map(ModContainer::getMetadata)
            .map(m -> m.getVersion().getFriendlyString());
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/DeviceProfile.java
git commit -m "feat(telemetry): device + version super-properties"
```

---

## Task 9: Wire `Telemetry.init()` into the client entrypoint

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java`

- [ ] **Step 1: Update the entrypoint**

Replace the body of `onInitializeClient()` in `FlashbackTurboClient.java` so it loads config, logs (now including `enableTelemetry`), then inits telemetry:

```java
    @Override
    public void onInitializeClient() {
        TurboConfig.load();
        var cfg = TurboConfig.current();
        LOGGER.info("[FlashbackTurbo] init — liftResolutionCap={} tuneFFmpegThreading={} parallelPngWriter={} (zlib L{}) showExportProgressOverlay={} useFragmentedMp4OnHwEncoders={} fixExportSetupRace={} enableTelemetry={}",
            cfg.liftResolutionCap, cfg.tuneFFmpegThreading, cfg.parallelPngWriter, cfg.pngCompressionLevel, cfg.showExportProgressOverlay, cfg.useFragmentedMp4OnHwEncoders, cfg.fixExportSetupRace, cfg.enableTelemetry);
        fr.zeffut.flashbackturbo.telemetry.Telemetry.init();
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java
git commit -m "feat(telemetry): init telemetry on client startup + log flag"
```

---

## Task 10: Instrument the hooks — emit `fbt_*` events at verified injection points

Add `Telemetry.capture(...)` and `ExportContext` calls to the three existing Mixins, at injection points already present in the code. These are the events that don't require reverse-engineering Flashback internals (timing + hook-local data).

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/mixin/exporting/ExportJobMixin.java`
- Modify: `src/main/java/fr/zeffut/flashbackturbo/mixin/gui/AsyncFFmpegFinishMixin.java`
- Modify: `src/main/java/fr/zeffut/flashbackturbo/mixin/encoder/AsyncFFmpegVideoWriterMixin.java`

- [ ] **Step 1: ExportJobMixin — mark export start + emit H10 recovery**

In `ExportJobMixin.java`, add the import:

```java
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import java.util.HashMap;
import java.util.Map;
```

Add a new `@Inject` at the HEAD of `setup` (export beginning), before the existing `flashbackturbo$ensureLevelLoaded`:

```java
    @Inject(method = "setup", at = @At("HEAD"), require = 0)
    private void flashbackturbo$telemetryExportStart(ReplayServer replayServer, CallbackInfo ci) {
        Telemetry.export().begin(System.nanoTime());
        Telemetry.capture("fbt_export_started", null);
    }
```

In the existing `flashbackturbo$ensureLevelLoaded`, replace the two terminal logging branches (the `if (mc.world == null) {...} else if (ticks > 0) {...}`) so they also emit the H10 telemetry event:

```java
        boolean timeoutHit = mc.world == null;
        long waitMs = (System.nanoTime() - (deadlineNs - 60_000_000_000L)) / 1_000_000L;
        if (timeoutHit) {
            FlashbackTurboClient.LOGGER.warn(
                "[H10] mc.level toujours null après {} runClientTick — race ExportJob.setup non contournée", ticks);
        } else if (ticks > 0) {
            FlashbackTurboClient.LOGGER.info(
                "[H10] monde du replay chargé après {} runClientTick supplémentaires — race ExportJob.setup contournée", ticks);
        }
        if (ticks > 0) {
            Map<String, Object> props = new HashMap<>();
            props.put("ticks_pumped", ticks);
            props.put("wait_ms", waitMs);
            props.put("timeout_hit", timeoutHit);
            Telemetry.capture("fbt_setup_race_recovered", props);
        }
```

> Note: the existing method computes `deadlineNs = System.nanoTime() + 60_000_000_000L` at entry. The `waitMs` line above recovers the elapsed time by subtracting the original 60s offset back out. Keep the existing `deadlineNs` declaration unchanged.

- [ ] **Step 2: AsyncFFmpegVideoWriterMixin — emit cap-lifted event**

In `AsyncFFmpegVideoWriterMixin.java`, add the import:

```java
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import java.util.Map;
```

In `flashbackturbo$liftResolutionCap`, inside the `if (TurboConfig.current().liftResolutionCap)` block, after the existing `LOGGER.info(...)` line, add:

```java
            Telemetry.capture("fbt_resolution_cap_lifted", Map.of("original_max_area", original));
```

- [ ] **Step 3: AsyncFFmpegFinishMixin — emit overlay-shown + export-finished**

In `AsyncFFmpegFinishMixin.java`, add the import:

```java
import fr.zeffut.flashbackturbo.telemetry.Telemetry;
import java.util.HashMap;
import java.util.Map;
```

Add a `@Unique` field next to the existing ones:

```java
    @Unique private long fbt$finishStartNs = 0L;
```

In `fbt$installSavingScreen`, after `this.fbt$savingActive = true;`, add:

```java
        this.fbt$finishStartNs = System.nanoTime();
```

Replace the body of `fbt$disableSaving` (the TAIL inject) so it emits both events. Keep the existing screen-cleanup logic:

```java
    @Inject(method = "finish", at = @At("TAIL"), require = 0)
    private void fbt$disableSaving(CallbackInfo ci) {
        long now = System.nanoTime();
        if (this.fbt$savingActive && this.fbt$finishStartNs > 0L) {
            long shownMs = (now - this.fbt$finishStartNs) / 1_000_000L;
            Telemetry.capture("fbt_saving_overlay_shown", Map.of("shown_ms", shownMs));
        }
        this.fbt$savingActive = false;

        // Émet fbt_export_finished avec les propriétés accumulées + duration_ms, puis clôt le contexte.
        if (Telemetry.export().isActive()) {
            Map<String, Object> props = new HashMap<>(Telemetry.export().snapshot(now));
            props.put("success", true);
            Telemetry.capture("fbt_export_finished", props);
            Telemetry.export().end();
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.currentScreen instanceof SavingExportScreen) {
            mc.setScreen(null);
        }
    }
```

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (mixins compile, tests still pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/mixin
git commit -m "feat(telemetry): instrument export start/finish + H4/H8/H10 hooks"
```

---

## Task 11: Runtime smoke test in the isolated test environment + verify events arrive

Per the project memory `feedback_testing`: never pollute the release jar; use the separate test env. This task validates events actually reach PostHog.

**Files:** none (runtime verification)

- [ ] **Step 1: Build the mod jar**

Run: `./gradlew build`
Expected: `build/libs/flashbackturbo-<version>.jar` produced, `jar tf` shows relocated posthog (re-verify Task 6 Step 6).

- [ ] **Step 2: Launch the isolated test environment**

Use the project's separate test environment (NOT the dev workspace, NOT the release pipeline — see memory `feedback_testing`). Install the freshly built jar alongside Flashback + Fabric API, launch Minecraft, and confirm the log line `[telemetry] initialisée (anonyme, host=https://eu.i.posthog.com)` appears.

- [ ] **Step 3: Perform one short export**

Record a tiny replay and export a short MP4. Confirm in logs: no telemetry-related exceptions, export completes normally.

- [ ] **Step 4: Verify events landed in PostHog**

Query PostHog (EU project 192659) for events named `fbt_mod_loaded`, `fbt_export_started`, `fbt_export_finished` in the last hour. Confirm at least `fbt_mod_loaded` and the export pair arrived, with `fbt_` super-properties (`fbt_version`, `flashback_version`, `os`, `cpu_cores`) populated and **no** PII (no paths/usernames in any property).

- [ ] **Step 5: Update the data schema doc note**

Append to the spec file `docs/superpowers/specs/2026-06-03-posthog-telemetry-design.md` a short "Verified events" line listing which `fbt_*` events were observed in PostHog, then:

```bash
git add docs/superpowers/specs/2026-06-03-posthog-telemetry-design.md
git commit -m "docs(telemetry): record verified events from smoke test"
```

---

## Deferred (not in this plan)

These spec events need Flashback-internal field access (resolution/encoder/framerate/frame-count/format/output-size) that requires reverse-engineering `ExportJob` / `ExportSettings` / the FFmpeg recorder, plus the PNG-writer throughput counters and the H9 fragmented-mp4 event. They are a clean follow-up once the core pipeline above is proven:

- `fbt_export_failed`, `fbt_export_cancelled` (need to hook Flashback's export exception/cancel paths).
- `fbt_fragmented_mp4_used` (H9), `fbt_parallel_png_used` (PNG writer counters).
- Enrich `fbt_export_started`/`fbt_export_finished` with `format`, `encoder`, `width`, `height`, `framerate`, `frame_count`, `output_size_bytes` — via `@Accessor`/`@ModifyArg` on the writer constructor. Investigative entry point: `javap -p -classpath "libs/Flashback-0.39.5-for-MC1.21.11.jar" com.moulberry.flashback.exporting.AsyncFFmpegVideoWriter com.moulberry.flashback.exporting.ExportJob` to find the exact field/param names, then feed them into `Telemetry.export().put(...)` at the constructor injection point.
