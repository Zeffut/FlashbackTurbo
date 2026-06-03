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
