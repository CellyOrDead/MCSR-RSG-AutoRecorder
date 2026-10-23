package dev.autorecorder.storage;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Read-only disk usage summary for the configured managed recordings directory. */
public final class StorageMonitor {
    public Snapshot read(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        long used = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p)).toList())
                    used = Math.addExact(used, Files.size(path));
            }
        }
        Path probe = root;
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        if (probe == null) throw new IOException("No existing parent folder for the configured output directory");
        FileStore store = Files.getFileStore(probe);
        return new Snapshot(used, store.getUsableSpace(), store.getTotalSpace());
    }

    public record Snapshot(long managedBytes, long availableBytes, long totalBytes) { }
}
