package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the compiled production facade with inert Fabric/config/SDK boundaries.
 * Never constructs the real SDK, reads a project token, or opens a network socket.
 * This verifies startup opt-out/dev, not runtime config reload or an in-game launch.
 */
class TelemetryInitIsolationTest {
    @TempDir Path temporary;

    @Test void disabledStartupDoesNotConsultFabricOrBuildSdk() throws Exception {
        verify(false, false, 0);
    }

    @Test void developmentStartupDoesNotAccessIdOrBuildSdk() throws Exception {
        verify(true, true, 1);
    }

    private void verify(boolean enabled, boolean development, int loaderCalls) throws Exception {
        Map<String, String> stubs = Map.of(
            "fr.zeffut.flashbackturbo.config.TurboConfig", """
                package fr.zeffut.flashbackturbo.config;
                public class TurboConfig {
                    public boolean enableTelemetry;
                    private static final TurboConfig INSTANCE = new TurboConfig();
                    public static TurboConfig current() { return INSTANCE; }
                }
                """,
            "net.fabricmc.loader.api.FabricLoader", """
                package net.fabricmc.loader.api;
                public interface FabricLoader {
                    static FabricLoader getInstance() { State.calls++; return new FabricLoader() {}; }
                    default boolean isDevelopmentEnvironment() { return State.development; }
                    default java.nio.file.Path getConfigDir() {
                        State.idAccess++; throw new AssertionError("Unexpected identifier access");
                    }
                    class State { public static boolean development; public static int calls, idAccess; }
                }
                """,
            "fr.zeffut.flashbackturbo.FlashbackTurboClient", """
                package fr.zeffut.flashbackturbo;
                public class FlashbackTurboClient {
                    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("isolated-test");
                }
                """,
            "com.posthog.java.PostHog", """
                package com.posthog.java;
                public class PostHog {
                    public static int builds, captures, shutdowns;
                    public static class Builder {
                        public Builder(String ignored) { builds++; }
                        public Builder host(String ignored) { return this; }
                        public PostHog build() { throw new AssertionError("Unexpected SDK build"); }
                    }
                    public void capture(String id, String event, java.util.Map<String,Object> props) { captures++; }
                    public void shutdown() { shutdowns++; }
                }
                """
        );
        var arguments = new java.util.ArrayList<String>();
        arguments.addAll(java.util.List.of("-d", temporary.toString(), "-classpath",
            Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        for (var entry : stubs.entrySet()) {
            Path file = temporary.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        URL production = Telemetry.class.getProtectionDomain().getCodeSource().getLocation();
        try (var isolated = new URLClassLoader(new URL[]{temporary.toUri().toURL(), production}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean replaced = name.equals(Telemetry.class.getName()) || stubs.keySet().stream()
                    .anyMatch(key -> name.equals(key) || name.startsWith(key + "$"));
                if (!replaced) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            Class<?> config = isolated.loadClass("fr.zeffut.flashbackturbo.config.TurboConfig");
            config.getField("enableTelemetry").set(config.getMethod("current").invoke(null), enabled);
            Class<?> state = isolated.loadClass("net.fabricmc.loader.api.FabricLoader$State");
            state.getField("development").setBoolean(null, development);
            Class<?> facade = isolated.loadClass(Telemetry.class.getName());
            facade.getMethod("init").invoke(null);
            facade.getMethod("capture", String.class, Map.class).invoke(null, "fixture", Map.of());
            facade.getMethod("captureForApp", String.class, String.class, String.class, String.class, String.class, Map.class)
                .invoke(null, "fixture", "fixture", "fixture", "26.3", "fixture", Map.of());
            facade.getMethod("shutdown").invoke(null);
            facade.getMethod("shutdown").invoke(null);
            var client = facade.getDeclaredField("client");
            client.setAccessible(true);
            assertNull(client.get(null));
            assertEquals(loaderCalls, state.getField("calls").getInt(null), "Guard must really execute, not fail earlier");
            assertEquals(0, state.getField("idAccess").getInt(null));
            Class<?> sdk = isolated.loadClass("com.posthog.java.PostHog");
            for (String counter : java.util.List.of("builds", "captures", "shutdowns"))
                assertEquals(0, sdk.getField(counter).getInt(null), counter);
        }
    }
}
