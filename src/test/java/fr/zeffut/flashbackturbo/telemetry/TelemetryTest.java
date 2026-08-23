package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void captureExportFailureWithThrowableDoesNotThrow() {
        assertDoesNotThrow(() ->
            Telemetry.captureExportFailure("export", new RuntimeException("boom /Users/x/world.mp4")));
    }

    @Test
    void captureExportFailureWithNullsDoesNotThrow() {
        assertDoesNotThrow(() -> Telemetry.captureExportFailure(null, null));
    }

    @Test
    void captureExportFailureEndsActiveContext() {
        Telemetry.export().begin(System.nanoTime());
        assertTrue(Telemetry.export().isActive());
        Telemetry.captureExportFailure("setup", new IllegalStateException("x"));
        assertFalse(Telemetry.export().isActive());
    }
}
