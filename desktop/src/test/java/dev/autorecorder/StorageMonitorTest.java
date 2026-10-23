package dev.autorecorder;

import dev.autorecorder.storage.StorageMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageMonitorTest {
    @TempDir Path temp;

    @Test void reportsManagedVideoBytesAndDriveSpace() throws Exception {
        Path root = Files.createDirectory(temp.resolve("recordings"));
        Files.write(root.resolve("game.mkv"), new byte[4096]);
        StorageMonitor.Snapshot snapshot = new StorageMonitor().read(root);
        assertEquals(4096, snapshot.managedBytes());
        assertTrue(snapshot.availableBytes() > 0);
        assertTrue(snapshot.totalBytes() >= snapshot.availableBytes());
    }
}
