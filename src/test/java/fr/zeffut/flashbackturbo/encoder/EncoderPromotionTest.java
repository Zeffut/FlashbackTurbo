package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EncoderPromotionTest {

    @Test
    void promotesLibopenh264WhenEnabledAndHwAvailable() {
        Optional<String> r = EncoderPromotion.choose("libopenh264", true, Optional.of("h264_nvenc"));
        assertEquals(Optional.of("h264_nvenc"), r);
    }

    @Test
    void doesNotPromoteWhenDisabled() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose("libopenh264", false, Optional.of("h264_nvenc")));
    }

    @Test
    void doesNotPromoteWhenNoHardware() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose("libopenh264", true, Optional.empty()));
    }

    @Test
    void doesNotPromoteNonSoftwareEncoder() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose("h264_nvenc", true, Optional.of("h264_qsv")));
    }

    @Test
    void handlesNullCurrentEncoder() {
        assertEquals(Optional.empty(),
            EncoderPromotion.choose(null, true, Optional.of("h264_nvenc")));
    }

    // chooseHevc tests

    @Test
    void promotesHevcMfWhenEnabledAndHwAvailable() {
        Optional<String> r = EncoderPromotion.chooseHevc("hevc_mf", true, Optional.of("hevc_nvenc"));
        assertEquals(Optional.of("hevc_nvenc"), r);
    }

    @Test
    void chooseHevcDoesNotPromoteWhenDisabled() {
        assertEquals(Optional.empty(),
            EncoderPromotion.chooseHevc("hevc_mf", false, Optional.of("hevc_nvenc")));
    }

    @Test
    void chooseHevcDoesNotPromoteWhenNoHardware() {
        assertEquals(Optional.empty(),
            EncoderPromotion.chooseHevc("hevc_mf", true, Optional.empty()));
    }

    @Test
    void chooseHevcDoesNotPromoteNonMfEncoder() {
        assertEquals(Optional.empty(),
            EncoderPromotion.chooseHevc("libx265", true, Optional.of("hevc_nvenc")));
    }

    @Test
    void chooseHevcHandlesNullCurrentEncoder() {
        assertEquals(Optional.empty(),
            EncoderPromotion.chooseHevc(null, true, Optional.of("hevc_nvenc")));
    }
}
