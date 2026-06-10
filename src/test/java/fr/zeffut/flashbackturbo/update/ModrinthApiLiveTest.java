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
 */
class ModrinthApiLiveTest {

    @Test
    @Disabled("réseau — activer manuellement")
    void detectsAnUpdateForAnOldZeffutJar() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        var versions = new com.google.gson.Gson().fromJson(
                get(http, "https://api.modrinth.com/v2/project/modchecker/version"),
                com.google.gson.JsonArray.class);
        assertFalse(versions.isEmpty(), "le projet doit avoir des versions");
        var oldest = versions.get(versions.size() - 1).getAsJsonObject();
        var files = oldest.getAsJsonArray("files");
        var file = files.get(0).getAsJsonObject();
        String url = file.get("url").getAsString();
        var oldestGameVersions = oldest.getAsJsonArray("game_versions");
        String mc = oldestGameVersions.get(oldestGameVersions.size() - 1).getAsString();

        Path tmp = Files.createTempFile("fbt-old-", ".jar");
        try (InputStream in = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Zeffut/test").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).body()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String hash = sha512(tmp);
        Files.deleteIfExists(tmp);

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
