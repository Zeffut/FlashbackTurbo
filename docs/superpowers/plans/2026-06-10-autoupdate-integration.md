# AutoUpdate Integration (FlashbackTurbo) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embarquer dans FlashbackTurbo le module AutoUpdate standard de Zeffut (mise à jour silencieuse via Modrinth de tous les mods du compte présents dans `mods/`, ce mod inclus), adapté à la stack Fabric-only de FlashbackTurbo.

**Architecture:** On copie les 3 classes du module (`UpdateService`, `ModrinthApi`, `JanitorMain`) dans `fr.zeffut.flashbackturbo.update`, en remplaçant l'abstraction multi-loader `Platform` (Stonecutter) par un **shim Fabric-only** local (FabricLoader). La config lit 4 options depuis `TurboConfig` (via une méthode `setting(key,def)`), la télémétrie reçoit `captureForApp(...)` + une coupure dev. Le module est MC-agnostique (zéro `net.minecraft`, gson + `java.net.http` du classpath du jeu) → aucun gating ni dépendance, et le build 26.1 (sed yarn→mojang) ne le touche pas. Démarré une fois depuis l'unique entrypoint client, derrière un lock JVM global partagé entre tous les mods Zeffut.

**Tech Stack:** Java 21, Fabric (fabric-loom), FabricLoader API, gson (classpath jeu), `java.net.http`, JUnit 5.

**Invariants partagés à NE JAMAIS renommer** (cohérence inter-mods Zeffut d'une même instance) : lock `zeffut.autoupdate.lock`, dossier `<gameDir>/.autoupdate/` (staging + state.json), sys props `autoupdate.enabled`/`autoupdate.mods.dir`/`autoupdate.api`, segment télémétrie `app=autoupdate`, events `upd_check_completed`/`upd_update_staged`/`upd_update_applied`/`upd_update_failed`, props `host_mod`+`updater_version`, `UPDATER_VERSION=1.0.0`.

---

## File Structure

- Create `src/main/java/fr/zeffut/flashbackturbo/update/Platform.java` — shim loader Fabric-only (FabricLoader).
- Create `src/main/java/fr/zeffut/flashbackturbo/update/ModrinthApi.java` — copie du module (package only).
- Create `src/main/java/fr/zeffut/flashbackturbo/update/JanitorMain.java` — copie du module (package only).
- Create `src/main/java/fr/zeffut/flashbackturbo/update/UpdateService.java` — copie du module + 3 adaptations.
- Modify `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java` — 4 champs + `setting(key,def)`.
- Modify `src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java` — `captureForApp(...)` + coupure dev dans `init()`.
- Modify `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java` — `UpdateService.start()`.
- Create `src/test/java/fr/zeffut/flashbackturbo/config/TurboConfigSettingTest.java` — test de `setting()`.
- Create `src/test/java/fr/zeffut/flashbackturbo/update/ModrinthApiLiveTest.java` — test d'intégration (API Modrinth réelle), taggé manuel.

Source de copie (lire, ne pas réécrire de mémoire) :
`~/Desktop/Projets/mc-mod-factory/template/src/main/java/fr/zeffut/modtemplate/update/{UpdateService,ModrinthApi,JanitorMain}.java`

---

## Task 1: Platform shim (Fabric-only)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/update/Platform.java`

> Pas de test unitaire : dépend de `FabricLoader.getInstance()` (runtime Fabric, non dispo en test). Vérifié au compile + en jeu.

- [ ] **Step 1: Écrire le shim**

```java
package fr.zeffut.flashbackturbo.update;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Abstraction loader minimale pour le module AutoUpdate, version Fabric-only de FlashbackTurbo
 * (remplace le Platform multi-loader Stonecutter du template). Référence UNIQUEMENT l'API
 * FabricLoader — aucune classe {@code net.minecraft} → le module reste MC-agnostique.
 */
public final class Platform {

    private Platform() {}

    /** Toujours "fabric" pour FlashbackTurbo. */
    public static String loader() {
        return "fabric";
    }

    /** Version conviviale de ce mod, ou "unknown". */
    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("flashbackturbo")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** Version conviviale de Minecraft, ou "unknown". */
    public static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** True si on tourne en environnement de développement. */
    public static boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /** Chemin absolu du répertoire d'instance/jeu. */
    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
```

- [ ] **Step 2: Compiler**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/update/Platform.java
git commit -m "feat(autoupdate): Platform shim Fabric-only (FabricLoader)"
```

---

## Task 2: Copier ModrinthApi + test d'intégration live

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/update/ModrinthApi.java`
- Create: `src/test/java/fr/zeffut/flashbackturbo/update/ModrinthApiLiveTest.java`

- [ ] **Step 1: Copier ModrinthApi.java**

Copier intégralement `~/Desktop/Projets/mc-mod-factory/template/src/main/java/fr/zeffut/modtemplate/update/ModrinthApi.java` vers `src/main/java/fr/zeffut/flashbackturbo/update/ModrinthApi.java`, en changeant UNIQUEMENT la première ligne :
`package fr.zeffut.modtemplate.update;` → `package fr.zeffut.flashbackturbo.update;`
Le reste (imports `com.google.gson.*`, `java.net.http.*`, logique) est inchangé. Lire le fichier source en entier et le reproduire à l'identique sauf le package.

- [ ] **Step 2: Écrire le test d'intégration live (API Modrinth réelle)**

Ce test prouve le cœur « détecter une mise à jour » de bout en bout contre la vraie API. Il est `@Disabled` par défaut (réseau) mais lançable explicitement.

```java
package fr.zeffut.flashbackturbo.update;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration réseau (désactivé par défaut). Télécharge une VIEILLE version d'un mod Zeffut
 * réel sur Modrinth, calcule son SHA-512, et vérifie que {@link ModrinthApi#latestVersions} renvoie
 * bien une version PLUS RÉCENTE pour le loader/MC de cette vieille version → prouve la détection.
 * Lancer : ./gradlew test --tests '*ModrinthApiLiveTest' -Dfbt.live=1
 */
class ModrinthApiLiveTest {

    @Test
    @Disabled("réseau — activer manuellement avec -Dfbt.live=1")
    void detectsAnUpdateForAnOldZeffutJar() throws Exception {
        // 1. Récupère la 1re version (la plus ancienne) d'un projet Zeffut connu (ModChecker).
        HttpClient http = HttpClient.newHttpClient();
        var versions = new com.google.gson.Gson().fromJson(
                get(http, "https://api.modrinth.com/v2/project/modchecker/version"),
                com.google.gson.JsonArray.class);
        assertFalse(versions.isEmpty(), "le projet doit avoir des versions");
        // La dernière entrée du tableau = la plus ancienne version publiée.
        var oldest = versions.get(versions.size() - 1).getAsJsonObject();
        var files = oldest.getAsJsonArray("files");
        var file = files.get(0).getAsJsonObject();
        String url = file.get("url").getAsString();
        var oldestGameVersions = oldest.getAsJsonArray("game_versions");
        String mc = oldestGameVersions.get(oldestGameVersions.size() - 1).getAsString();

        // 2. Télécharge ce vieux jar + hash SHA-512.
        Path tmp = Files.createTempFile("fbt-old-", ".jar");
        try (InputStream in = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Zeffut/test").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).body()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String hash = sha512(tmp);
        Files.deleteIfExists(tmp);

        // 3. latestVersions doit trouver une version, et son numéro doit différer de la vieille.
        Map<String, ModrinthApi.LatestVersion> latest =
                new ModrinthApi("test").latestVersions(java.util.List.of(hash), "fabric", mc);
        ModrinthApi.LatestVersion lv = latest.get(hash);
        assertNotNull(lv, "Modrinth doit résoudre le hash du vieux jar");
        assertNotEquals(oldest.get("version_number").getAsString(), lv.versionNumber(),
                "la dernière version doit différer de la plus ancienne");
        assertTrue(lv.fileName().endsWith(".jar"));
        assertEquals(128, lv.sha512().length(), "SHA-512 hex = 128 chars");
    }

    private static String get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Zeffut/test").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536]; int r;
            while ((r = in.read(buf)) >= 0) d.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : d.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

- [ ] **Step 3: Compiler + lancer le test live**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.
Run (réseau, manuel) : `./gradlew test --tests '*ModrinthApiLiveTest' -Dfbt.live=1` après avoir retiré `@Disabled` OU en le lançant via l'IDE. Si l'environnement de CI bloque le réseau, laisser `@Disabled` et noter que le test est manuel.
Expected: PASS — la détection renvoie une version récente ≠ la plus ancienne.

> Note : ce test reste `@Disabled` dans la suite normale (`./gradlew test`) pour ne pas dépendre du réseau. Il sert de preuve manuelle du cœur de résolution.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/update/ModrinthApi.java src/test/java/fr/zeffut/flashbackturbo/update/ModrinthApiLiveTest.java
git commit -m "feat(autoupdate): ModrinthApi (copie module) + test d'intégration live"
```

---

## Task 3: Copier JanitorMain

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/update/JanitorMain.java`

> Pas de test (process spawner JDK pur). Compile-vérifié.

- [ ] **Step 1: Copier JanitorMain.java**

Copier intégralement `~/Desktop/Projets/mc-mod-factory/template/src/main/java/fr/zeffut/modtemplate/update/JanitorMain.java` vers `src/main/java/fr/zeffut/flashbackturbo/update/JanitorMain.java`, en changeant UNIQUEMENT le package : `fr.zeffut.modtemplate.update` → `fr.zeffut.flashbackturbo.update`. Reste inchangé (zéro import à part `java.nio.file.*`). Lire le source en entier et le reproduire à l'identique sauf le package.

- [ ] **Step 2: Compiler + commit**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.
```bash
git add src/main/java/fr/zeffut/flashbackturbo/update/JanitorMain.java
git commit -m "feat(autoupdate): JanitorMain (copie module, JDK pur)"
```

---

## Task 4: TurboConfig — 4 options + setting()

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java`
- Create: `src/test/java/fr/zeffut/flashbackturbo/config/TurboConfigSettingTest.java`

- [ ] **Step 1: Écrire le test qui échoue**

`TurboConfig` a un constructeur privé mais `current()` renvoie une instance avec les défauts. On teste le mapping `setting()` sur l'instance par défaut.

```java
package fr.zeffut.flashbackturbo.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurboConfigSettingTest {

    @Test
    void settingMapsAutoUpdateKeysWithDefaults() {
        TurboConfig c = TurboConfig.current();
        assertEquals("true", c.setting("auto_update", "true"));
        assertEquals("false", c.setting("update_all", "false"));
        assertEquals("Zeffut", c.setting("update_owner", "x"));
        assertEquals("", c.setting("update_exclude", "fallback"));
    }

    @Test
    void settingReturnsFallbackForUnknownKey() {
        assertEquals("def", TurboConfig.current().setting("nope", "def"));
    }
}
```

- [ ] **Step 2: Lancer le test → échoue**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.config.TurboConfigSettingTest'`
Expected: FAIL — `setting` n'existe pas (compile error).

- [ ] **Step 3: Ajouter les 4 champs + la méthode**

Dans `TurboConfig.java`, après le champ `enableTelemetry` (juste avant le constructeur privé `private TurboConfig() {}`), ajouter :

```java
    /** AutoUpdate : mise à jour silencieuse des mods Zeffut via Modrinth (défaut activé). */
    public boolean autoUpdate = true;
    /** AutoUpdate : si true, met à jour TOUS les mods Modrinth (pas seulement ceux de update_owner). */
    public boolean updateAll = false;
    /** AutoUpdate : compte Modrinth dont les mods sont éligibles à la mise à jour. */
    public String updateOwner = "Zeffut";
    /** AutoUpdate : slugs/ids de projets à exclure de la mise à jour, séparés par des virgules. */
    public String updateExclude = "";

    /**
     * Accès « clé→valeur string » utilisé par le module AutoUpdate (mappe les 4 options ci-dessus).
     * Renvoie {@code fallback} pour toute clé inconnue.
     */
    public String setting(String key, String fallback) {
        return switch (key) {
            case "auto_update" -> String.valueOf(autoUpdate);
            case "update_all" -> String.valueOf(updateAll);
            case "update_owner" -> updateOwner != null ? updateOwner : fallback;
            case "update_exclude" -> updateExclude != null ? updateExclude : fallback;
            default -> fallback;
        };
    }
```

- [ ] **Step 4: Lancer le test → passe**

Run: `./gradlew test --tests 'fr.zeffut.flashbackturbo.config.TurboConfigSettingTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/config/TurboConfig.java src/test/java/fr/zeffut/flashbackturbo/config/TurboConfigSettingTest.java
git commit -m "feat(autoupdate): options auto_update/update_* dans TurboConfig + setting()"
```

---

## Task 5: Telemetry — captureForApp + coupure dev

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java`

> Pas de test unitaire (client PostHog réel). Compile-vérifié + comportement validé en jeu (Task 8 : telemetry off en dev).

- [ ] **Step 1: Ajouter la coupure dev dans init()**

Dans `Telemetry.init()`, juste après le bloc `if (!TurboConfig.current().enableTelemetry) { ... return; }` (donc avant la création du `distinctId`), ajouter :

```java
            if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
                FlashbackTurboClient.LOGGER.info("[telemetry] désactivée (environnement de développement)");
                return; // client reste null → capture()/captureForApp() no-op ; AutoUpdate continue de logger
            }
```
(`FabricLoader` est déjà importé dans ce fichier.)

- [ ] **Step 2: Ajouter captureForApp(...)**

Dans `Telemetry.java`, après la méthode `capture(String event, Map<String,Object> props)`, ajouter :

```java
    /**
     * Comme {@link #capture}, mais segmente l'event sous un {@code app} explicite (ex. "autoupdate")
     * et attache source / versions MC & mod. Utilisé par les modules transverses (AutoUpdate) qui
     * partagent le projet PostHog. No-op si non initialisée (donc aussi en dev). Ne lève jamais.
     */
    public static void captureForApp(String app, String event, String source,
                                     String mcVersion, String modVersion, Map<String, Object> props) {
        try {
            PostHog c = client;
            if (c == null || distinctId == null) return;
            Map<String, Object> merged = new HashMap<>(superProps);
            if (props != null) merged.putAll(props);
            merged.put("app", app);
            if (source != null) merged.put("source", source);
            if (mcVersion != null) merged.put("mc_version", mcVersion);
            if (modVersion != null) merged.put("mod_version", modVersion);
            c.capture(distinctId, event, merged);
        } catch (Throwable t) {
            FlashbackTurboClient.LOGGER.debug("[telemetry] captureForApp('{}') échouée", event, t);
        }
    }
```

> `startHeartbeat` n'est PAS ajouté : le module AutoUpdate ne l'utilise pas (décision : éviter un changement de comportement télémétrie sans rapport — voir le commit message).

- [ ] **Step 3: Compiler + commit**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.
```bash
git add src/main/java/fr/zeffut/flashbackturbo/telemetry/Telemetry.java
git commit -m "feat(autoupdate): Telemetry.captureForApp + coupure télémétrie en dev (sans startHeartbeat, non utilisé)"
```

---

## Task 6: Copier UpdateService (avec les 3 adaptations)

**Files:**
- Create: `src/main/java/fr/zeffut/flashbackturbo/update/UpdateService.java`

> Pas de test unitaire (couplé à Platform/FabricLoader + I/O). Compile + Task 8.

- [ ] **Step 1: Copier UpdateService.java en appliquant les adaptations**

Lire intégralement `~/Desktop/Projets/mc-mod-factory/template/src/main/java/fr/zeffut/modtemplate/update/UpdateService.java` (423 lignes) et le reproduire dans `src/main/java/fr/zeffut/flashbackturbo/update/UpdateService.java` à l'identique, SAUF exactement ces 4 points :

1. **Package** : `package fr.zeffut.modtemplate.update;` → `package fr.zeffut.flashbackturbo.update;`
2. **Imports à retirer/adapter** : supprimer `import fr.zeffut.modtemplate.config.ModConfig;` et `import fr.zeffut.modtemplate.platform.Platform;` et `import fr.zeffut.modtemplate.telemetry.Telemetry;`. Ajouter `import fr.zeffut.flashbackturbo.config.TurboConfig;` et `import fr.zeffut.flashbackturbo.telemetry.Telemetry;`. (`Platform` est désormais dans le MÊME package `fr.zeffut.flashbackturbo.update` → aucun import nécessaire.)
3. **HOST_MOD_ID** : `private static final String HOST_MOD_ID = "__MOD_ID__";` → `= "flashbackturbo";`
4. **Lecture config** : remplacer les usages de `ModConfig` par `TurboConfig`. Concrètement, dans `run()` :
   `ModConfig cfg = ModConfig.get();` → `TurboConfig cfg = TurboConfig.current();`
   et dans `enabled()` :
   `return "true".equalsIgnoreCase(ModConfig.get().setting("auto_update", "true"));` →
   `return "true".equalsIgnoreCase(TurboConfig.current().setting("auto_update", "true"));`
   Les appels `cfg.setting("update_all", ...)`, `cfg.setting("update_owner", ...)`, `cfg.setting("update_exclude", ...)` restent identiques (TurboConfig fournit `setting`).

Tout le reste (Platform.loader()/mcVersion()/modVersion()/gameDir(), les invariants GLOBAL_LOCK_KEY/`.autoupdate`/sys props/`app=autoupdate`/`upd_*`/UPDATER_VERSION, la logique scan/resolve/stage/swap/reconcile/janitor) est COPIÉ TEL QUEL.

- [ ] **Step 2: Compiler**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (Si une référence `Platform`/`Telemetry`/`TurboConfig` ne résout pas, vérifier imports/package ; ne PAS inventer d'API.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/zeffut/flashbackturbo/update/UpdateService.java
git commit -m "feat(autoupdate): UpdateService (copie module + adaptations Fabric/TurboConfig/HOST_MOD_ID)"
```

---

## Task 7: Brancher dans l'entrypoint

**Files:**
- Modify: `src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java`

- [ ] **Step 1: Démarrer le service après l'init télémétrie**

Dans `FlashbackTurboClient.onInitializeClient()`, immédiatement après la ligne `fr.zeffut.flashbackturbo.telemetry.Telemetry.init();`, ajouter :

```java
        // AutoUpdate : mise à jour silencieuse des mods Zeffut via Modrinth (thread daemon, lock global).
        fr.zeffut.flashbackturbo.update.UpdateService.start();
```

- [ ] **Step 2: Compiler + suite de tests + commit**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, tous les tests verts (le test live reste `@Disabled`).
```bash
git add src/main/java/fr/zeffut/flashbackturbo/FlashbackTurboClient.java
git commit -m "feat(autoupdate): démarrer UpdateService depuis l'entrypoint client"
```

---

## Task 8: Vérifications — build matrice, javap, pipeline

**Files:** (aucun fichier de prod ; vérifications)

- [ ] **Step 1: Build matrice complète**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL ; jar `build/libs/flashbackturbo-<ver>.jar`. Vérifier PostHog relocalisé :
`jar tf build/libs/flashbackturbo-*.jar | grep -c '^fr/zeffut/flashbackturbo/shadow/posthog/'` > 0.
Run: `./scripts/build-26.1.sh`
Expected: `[26.1] ✅ build OK (shadé)`. (Le module update n'a aucun nom yarn → le sed ne le touche pas ; FabricLoader identique en 26.1.)

- [ ] **Step 2: javap — zéro net/minecraft dans le module update**

Extraire les .class du module depuis le jar 1.21.x et vérifier l'absence de référence `net/minecraft` :
```bash
J=$(ls build/libs/flashbackturbo-*.jar | grep -v sources | head -1)
TMP=$(mktemp -d); (cd "$TMP" && jar xf "$OLDPWD/$J" 'fr/zeffut/flashbackturbo/update/')
for c in "$TMP"/fr/zeffut/flashbackturbo/update/*.class; do
  echo "== $c =="; javap -c -p "$c" | grep -c 'net/minecraft' || true
done
```
Expected: `0` pour chaque classe (`UpdateService`, `ModrinthApi`, `JanitorMain`, `Platform`, et classes internes). FabricLoader (`net/fabricmc/...`) est autorisé — on ne cherche QUE `net/minecraft`.

- [ ] **Step 3: Test pipeline — harness headless (autonome, fiable)**

Le runClient dev de FlashbackTurbo nécessite Flashback + ses deps JIJ en runtime (fragile, cf. sessions précédentes). La vérification autonome fiable du PIPELINE se fait via le test d'intégration live de la Task 2 (résolution Modrinth réelle) :
Run (réseau) : retirer `@Disabled` de `ModrinthApiLiveTest` (ou lancer via IDE) puis `./gradlew test --tests '*ModrinthApiLiveTest'`.
Expected: PASS — détection d'une version récente pour un vieux jar Zeffut réel. Remettre `@Disabled` après.

- [ ] **Step 4: Test EN JEU (best-effort) — runClient**

Tenter le test in-game complet si le runtime dev est disponible (Flashback + deps via `modLocalRuntime`, NON committé) :
1. Placer un VIEUX jar d'un mod Zeffut (ex. une ancienne version de ModChecker pour la MC courante, récupérée depuis Modrinth) dans un dossier de test `/tmp/fbt-mods-test/`.
2. `./gradlew runClient -Dautoupdate.mods.dir=/tmp/fbt-mods-test` (ou forwarder la prop via la config de run loom).
3. Logs attendus : `[AutoUpdate] staged update for <slug>: ... -> <newer>` puis `[AutoUpdate] check completed`. `telemetry=false` (init log « environnement de développement »).
4. Fermer le client (SIGTERM) → vérifier sur disque : vieux jar supprimé, nouveau présent dans `/tmp/fbt-mods-test/`.
5. Relancer `runClient` → log `[AutoUpdate] update applied: ...` et **0 update re-stagée** (idempotence).

> ⚠️ Si le runtime dev ne se lance pas (deps Flashback manquantes / crash chargement), NE PAS bloquer la livraison du CODE : marquer ce step « non vérifié en jeu en autonomie », s'appuyer sur Step 3 (pipeline réseau prouvé) + build + javap, et le signaler à l'utilisateur avant publication.

- [ ] **Step 5: Commit (si ajustements de build/run nécessaires)**

```bash
git add -A && git commit -m "test(autoupdate): vérifs build matrice + javap zéro net.minecraft + pipeline Modrinth"
```

---

## Task 9: Bump version + publication (GATE GO utilisateur)

> La publication diffuse le module aux joueurs. Cf. [[feedback_bench_and_publish]], [[reference_publishing]], [[feedback_releases]] (ne JAMAIS écraser une version publiée). **DEMANDER LE GO EXPLICITE DE L'UTILISATEUR avant de publier.**

- [ ] **Step 1: Bump de version mineure**

Dans `gradle.properties`, incrémenter `mod_version` (ex. `0.5.0` → `0.6.0`). Mettre à jour le statut/compat du `README.md` et documenter AutoUpdate (1 ligne : mise à jour silencieuse des mods Zeffut, opt-out via `autoUpdate:false`).

- [ ] **Step 2: Rebuild propre des 2 variantes**

Run: `./gradlew clean build` puis `./scripts/build-26.1.sh`
Expected: deux jars 0.6.0, PostHog relocalisé dans les deux.

- [ ] **Step 3: ATTENDRE LE GO UTILISATEUR**

Présenter : récap (build vert, javap OK, pipeline prouvé, test in-game fait ou non), versions à publier, changelog. **Ne pas publier sans « go » explicite.**

- [ ] **Step 4: Publier (après GO)**

Vérifier le token (`curl .../v2/user` → 200). Publier les 2 variantes via le flux habituel (`POST /v2/version` multipart, ou `publishMods`) : `0.6.0` (MC 1.21.9/10/11) et `0.6.0+26.1` (MC 26.1/26.1.1/26.1.2), deps required Flashback `4das1Fjq` + Fabric API `P7dR8mSH`. Releases GitHub `v0.6.0` + `v0.6.0+26.1`. PATCH du body Modrinth (mentionner AutoUpdate). NE PAS toucher aux versions déjà publiées.

- [ ] **Step 5: Commit + tag**

```bash
git add gradle.properties README.md
git commit -m "release(0.6.0): module AutoUpdate embarqué"
git tag v0.6.0 && git push && git push --tags
```

---

## Notes d'exécution

- Le module est **fail-safe et invisible** : tout `run()` est sous `try/catch(Throwable)`, les events télémétrie no-op si non init.
- **Aucune dépendance Gradle ajoutée** : gson + `java.net.http` + FabricLoader sont déjà au classpath.
- **Aucun projet Modrinth** pour le module lui-même (c'est du code embarqué).
- La publication 0.6.0 est l'unique vecteur de diffusion du module — d'où le GATE GO.
