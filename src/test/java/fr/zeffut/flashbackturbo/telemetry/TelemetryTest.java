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
