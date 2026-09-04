package fr.zeffut.flashbackturbo.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SanitizerTest {

    @Test
    void stripsUnixHomePaths() {
        String in = "failed to open /Users/alice/replays/my world.mp4";
        String out = Sanitizer.sanitizeMessage(in);
        assertFalse(out.contains("alice"));
        assertFalse(out.contains("my world"));
        assertTrue(out.contains("<path>"));
    }

    @Test
    void stripsWindowsPaths() {
        String in = "C:\\Users\\Bob\\AppData\\replay.mkv missing";
        String out = Sanitizer.sanitizeMessage(in);
        assertFalse(out.contains("Bob"));
        assertTrue(out.contains("<path>"));
    }

    @Test
    void stripsIpAddresses() {
        String in = "connection lost to 192.168.1.42:25565";
        String out = Sanitizer.sanitizeMessage(in);
        assertFalse(out.contains("192.168.1.42"));
        assertTrue(out.contains("<ip>"));
    }

    @Test
    void nullMessageBecomesEmpty() {
        assertEquals("", Sanitizer.sanitizeMessage(null));
    }

    @Test
    void topFramesReturnsClassAndMethodOnly() {
        Throwable t = new RuntimeException("boom");
        String frames = Sanitizer.topFrames(t, 3);
        assertTrue(frames.contains("SanitizerTest"));
        assertFalse(frames.contains(".java:")); // pas de fichier:ligne local
    }

    @Test
    void topFramesHandlesNull() {
        assertEquals("", Sanitizer.topFrames(null, 3));
    }
}
