package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GpuInfoTest {

    @BeforeEach
    void reset() { GpuInfo.resetForTest(); }

    @Test
    void emptyByDefault() {
        assertTrue(GpuInfo.snapshot().isEmpty());
    }

    @Test
    void snapshotContainsVendorAndRendererOnceSet() {
        GpuInfo.setForTest("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2");
        Map<String, Object> p = GpuInfo.snapshot();
        assertEquals("NVIDIA Corporation", p.get("gpu_vendor"));
        assertEquals("NVIDIA GeForce RTX 3070/PCIe/SSE2", p.get("gpu_renderer"));
    }

    @Test
    void blankValuesAreOmitted() {
        GpuInfo.setForTest("", "   ");
        assertTrue(GpuInfo.snapshot().isEmpty());
    }
}
