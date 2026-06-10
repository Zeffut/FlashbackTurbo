package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class HwEncoderProbeTest {

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
}
