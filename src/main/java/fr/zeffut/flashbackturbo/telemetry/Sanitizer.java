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
