package dev.autorecorder;

import dev.autorecorder.recording.QuarantineManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuarantineManagerTest {
    @TempDir Path temp;

    @Test void onlyMovesARegularRecordingInsideConfiguredRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("recordings"));
        Path file = Files.createDirectories(root.resolve("gameplay")).resolve("attempt.mkv");
        Files.writeString(file, "video");
        Path moved = new QuarantineManager().quarantine(file, root.toString(), UUID.randomUUID());
        assertTrue(moved.startsWith(root.resolve("quarantine")));
        assertFalse(Files.exists(file));
        assertEquals("video", Files.readString(moved));
    }

    @Test void refusesFilesOutsideTheManagedFolderAndDoesNotTouchThem() throws Exception {
        Path root = Files.createDirectory(temp.resolve("recordings"));
        Path outside = Files.writeString(temp.resolve("keep.mkv"), "important");
        assertThrows(IOException.class, () -> new QuarantineManager().quarantine(outside, root.toString(), UUID.randomUUID()));
        assertEquals("important", Files.readString(outside));
    }

    @Test void refusesToQuarantineAFileTwice() throws Exception {
        Path root = Files.createDirectory(temp.resolve("recordings"));
        Path file = Files.createDirectories(root.resolve("gameplay")).resolve("attempt.mkv");
        Files.writeString(file, "video");
        Path quarantined = new QuarantineManager().quarantine(file, root.toString(), UUID.randomUUID());
        assertThrows(IOException.class, () -> new QuarantineManager().quarantine(quarantined, root.toString(), UUID.randomUUID()));
    }
}
