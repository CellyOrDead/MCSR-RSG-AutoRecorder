package dev.autorecorder.recording;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Moves an already finalized attempt file into an app-owned quarantine folder. */
public final class QuarantineManager {
    public Path quarantine(Path recording, String outputRoot, UUID attemptId) throws IOException {
        Path root = Path.of(outputRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        root = root.toRealPath();

        Path source = recording.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source))
            throw new IOException("Recording is not a regular, non-symlink file");
        Path sourceReal = source.toRealPath();
        if (!sourceReal.startsWith(root)) throw new IOException("Refusing to move a file outside the configured recording folder");

        Path quarantine = root.resolve("quarantine").normalize();
        if (!quarantine.startsWith(root) || sourceReal.startsWith(quarantine))
            throw new IOException("Refusing an invalid or already quarantined recording path");
        Files.createDirectories(quarantine);
        quarantine = quarantine.toRealPath();
        if (!quarantine.startsWith(root)) throw new IOException("Quarantine folder resolves outside the configured recording folder");

        String name = attemptId + "_" + source.getFileName();
        Path destination = quarantine.resolve(name).normalize();
        if (!destination.startsWith(quarantine) || Files.exists(destination))
            throw new IOException("Quarantine destination already exists or is invalid");
        try {
            return Files.move(sourceReal, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(sourceReal, destination);
        }
    }
}
