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
