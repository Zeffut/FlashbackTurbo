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
