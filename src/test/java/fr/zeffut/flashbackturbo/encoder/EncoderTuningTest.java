package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EncoderTuningTest {

    @Test
    void slicesEqualsCoresWhenBelowCap() {
        assertEquals(6, EncoderTuning.openh264Slices(6));
    }

    @Test
    void slicesCappedAtEight() {
        assertEquals(8, EncoderTuning.openh264Slices(16));
    }

    @Test
    void slicesAtLeastOne() {
        assertEquals(1, EncoderTuning.openh264Slices(0));
        assertEquals(1, EncoderTuning.openh264Slices(-4));
    }
}
