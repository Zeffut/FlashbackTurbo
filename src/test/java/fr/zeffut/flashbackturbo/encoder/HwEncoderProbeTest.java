package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class HwEncoderProbeTest {

    @BeforeEach
    void resetProbeCache() { HwEncoderProbe.resetForTest(); }

    @Test
    void picksFirstCandidateThatOpens() {
        Predicate<String> opener = name -> name.equals("h264_qsv");
        Optional<String> r = HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener);
        assertEquals(Optional.of("h264_qsv"), r);
    }

    @Test
    void prefersEarlierCandidateWhenBothOpen() {
        Predicate<String> opener = name -> true;
        Optional<String> r = HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener);
        assertEquals(Optional.of("h264_nvenc"), r);
    }

    @Test
    void emptyWhenNoneOpen() {
        Predicate<String> opener = name -> false;
        assertEquals(Optional.empty(),
            HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener));
    }

    @Test
    void openerThrowingIsTreatedAsUnavailable() {
        Predicate<String> opener = name -> { throw new RuntimeException("driver boom"); };
        assertEquals(Optional.empty(),
            HwEncoderProbe.select(List.of("h264_nvenc"), opener));
    }

    @Test
    void defaultCandidatesAreNvencThenQsvOnly() {
        assertEquals(List.of("h264_nvenc", "h264_qsv"), HwEncoderProbe.DEFAULT_CANDIDATES);
    }

    @Test
    void bestHardwareMemoizesAfterFirstProbe() {
        int[] calls = {0};
        Predicate<String> counting = name -> { calls[0]++; return name.equals("h264_qsv"); };
        Optional<String> first = HwEncoderProbe.bestH264Hardware(counting);
        Optional<String> second = HwEncoderProbe.bestH264Hardware(counting);
        assertEquals(Optional.of("h264_qsv"), first);
        assertEquals(Optional.of("h264_qsv"), second);
        // nvenc(skip)+qsv(hit) = 2 calls on the first probe, 0 on the memoized second
        assertEquals(2, calls[0]);
    }

    @Test
    void lastResultPopulatedAfterProbe() {
        HwEncoderProbe.bestH264Hardware(name -> name.equals("h264_nvenc"));
        HwEncoderProbe.ProbeResult r = HwEncoderProbe.lastResult();
        assertNotNull(r);
        assertEquals("h264_nvenc", r.selected());
        assertEquals(List.of("h264_nvenc", "h264_qsv"), r.probed());
        assertTrue(r.probeMs() >= 0);
    }

    @Test
    void selectHandlesEmptyCandidateList() {
        assertEquals(Optional.empty(), HwEncoderProbe.select(List.of(), name -> true));
    }

    // HEVC probe tests

    @Test
    void hevcCandidatesAreNvencThenQsvOnly() {
        assertEquals(List.of("hevc_nvenc", "hevc_qsv"), HwEncoderProbe.HEVC_CANDIDATES);
    }

    @Test
    void bestHevcHardwareMemoizesAfterFirstProbe() {
        int[] calls = {0};
        Predicate<String> counting = name -> { calls[0]++; return name.equals("hevc_qsv"); };
        Optional<String> first = HwEncoderProbe.bestHevcHardware(counting);
        Optional<String> second = HwEncoderProbe.bestHevcHardware(counting);
        assertEquals(Optional.of("hevc_qsv"), first);
        assertEquals(Optional.of("hevc_qsv"), second);
        // nvenc(skip)+qsv(hit) = 2 calls on the first probe, 0 on the memoized second
        assertEquals(2, calls[0]);
    }

    @Test
    void lastHevcResultPopulatedAfterProbe() {
        HwEncoderProbe.bestHevcHardware(name -> name.equals("hevc_nvenc"));
        HwEncoderProbe.ProbeResult r = HwEncoderProbe.lastHevcResult();
        assertNotNull(r);
        assertEquals("hevc_nvenc", r.selected());
        assertEquals(List.of("hevc_nvenc", "hevc_qsv"), r.probed());
        assertTrue(r.probeMs() >= 0);
    }

    @Test
    void hevcAndH264CachesAreIndependent() {
        HwEncoderProbe.bestH264Hardware(name -> name.equals("h264_nvenc"));
        HwEncoderProbe.bestHevcHardware(name -> false);
        assertEquals(Optional.of("h264_nvenc"), HwEncoderProbe.bestH264Hardware(name -> true));
        assertEquals(Optional.empty(), HwEncoderProbe.bestHevcHardware(name -> true));
    }
}
