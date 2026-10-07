package fr.zeffut.flashbackturbo.encoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production lifecycle with real temporary files, but no native recorder. */
class HwEncoderProbeCleanupTest {
    private final List<File> files = new ArrayList<>();

    @AfterEach
    void removeFilesEvenWhenAnAssertionFails() throws IOException {
        for (File file : files) Files.deleteIfExists(file.toPath());
    }

    private File track(File file) {
        files.add(file);
        assertTrue(file.isFile(), "temporary file exists before recorder creation");
        assertTrue(file.getName().startsWith("fbt-probe-"));
        assertTrue(file.getName().endsWith(".mp4"));
        return file;
    }

    private boolean probe(FakeRecorder recorder) {
        return HwEncoderProbe.opener(file -> {
            recorder.file = track(file);
            return recorder;
        }).test("h264_nvenc");
    }

    private void assertCleaned(FakeRecorder recorder, String... calls) {
        assertAll(
            () -> assertEquals(List.of(calls), recorder.calls),
            () -> assertFalse(recorder.file.exists(), "probe must delete its temporary file"));
    }

    @Test
    void startFailureReleasesWithoutStoppingAndDeletesTemp() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.startFailure = new IOException("start failed");
        assertFalse(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "release");
    }

    @Test
    void frameFailureStopsReleasesAndDeletesTemp() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.frameFailure = new IOException("frame failed");
        assertFalse(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "frame", "stop", "release");
    }

    @Test
    void startErrorStillReleasesAndDeletesTemp() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.startFailure = new UnsatisfiedLinkError("native unavailable");
        assertFalse(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "release");
    }

    @Test
    void frameErrorStillStopsReleasesAndDeletesTemp() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.frameFailure = new LinkageError("native frame unavailable");
        assertFalse(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "frame", "stop", "release");
    }

    @Test
    void stopAndReleaseErrorsDoNotPreventDeletionOrEscape() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.frameFailure = new IOException("frame failed");
        recorder.stopFailure = new LinkageError("stop failed");
        recorder.releaseFailure = new LinkageError("release failed");
        assertFalse(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "frame", "stop", "release");
    }

    @Test
    void releaseErrorAfterFailedStartStillDeletesTemp() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.startFailure = new IOException("start failed");
        recorder.releaseFailure = new LinkageError("release failed");
        assertFalse(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "release");
    }

    @Test
    void successfulProbeStopsReleasesAndDeletesTemp() {
        FakeRecorder recorder = new FakeRecorder();
        assertTrue(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "frame", "stop", "release");
    }

    @Test
    void cleanupErrorsPreserveSuccessfulProbeResult() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.stopFailure = new IOException("stop failed");
        recorder.releaseFailure = new IOException("release failed");
        assertTrue(probe(recorder));
        assertCleaned(recorder, "start:h264_nvenc", "frame", "stop", "release");
    }

    @Test
    void factoryFailureDeletesAlreadyCreatedTemp() {
        assertFalse(HwEncoderProbe.opener(file -> {
            track(file);
            throw new IOException("construction failed");
        }).test("h264_nvenc"));
        assertEquals(1, files.size());
        assertFalse(files.getFirst().exists());
    }

    @Test
    void failedCandidateIsCleanedBeforeNextCandidateStarts() {
        FakeRecorder first = new FakeRecorder();
        first.frameFailure = new IOException("first frame failed");
        FakeRecorder second = new FakeRecorder();
        var opener = HwEncoderProbe.opener(file -> {
            track(file);
            if (files.size() == 1) {
                first.file = file;
                return first;
            }
            assertCleaned(first, "start:h264_nvenc", "frame", "stop", "release");
            second.file = file;
            return second;
        });
        assertEquals(Optional.of("h264_qsv"),
            HwEncoderProbe.select(List.of("h264_nvenc", "h264_qsv"), opener));
        assertEquals(2, files.size());
        assertCleaned(second, "start:h264_qsv", "frame", "stop", "release");
    }

    private static class FakeRecorder implements HwEncoderProbe.ProbeRecorder {
        File file;
        final List<String> calls = new ArrayList<>();
        Throwable startFailure;
        Throwable frameFailure;
        Throwable stopFailure;
        Throwable releaseFailure;

        private void failIfRequested(Throwable failure) throws Exception {
            if (failure instanceof Error error) throw error;
            if (failure instanceof Exception exception) throw exception;
        }

        public void start(String name) throws Exception {
            calls.add("start:" + name);
            failIfRequested(startFailure);
        }

        public void recordFrame() throws Exception {
            calls.add("frame");
            // Simulate partial output, not just an empty file that never got used.
            Files.writeString(file.toPath(), "partial frame");
            failIfRequested(frameFailure);
        }

        public void stop() throws Exception {
            calls.add(file.exists() ? "stop" : "stop:temp-already-deleted");
            failIfRequested(stopFailure);
        }

        public void release() throws Exception {
            calls.add(file.exists() ? "release" : "release:temp-already-deleted");
            failIfRequested(releaseFailure);
        }
    }
}
