package dev.autorecorder.obs;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/** Stores the OBS password encrypted for the current Windows user with DPAPI. */
public final class ObsPasswordStore {
    private final Path file;

    public ObsPasswordStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Optional<String> load() throws IOException {
        if (!isWindows() || !Files.isRegularFile(file)) return Optional.empty();
        byte[] protectedBytes = Files.readAllBytes(file);
        byte[] clearBytes = null;
        try {
            clearBytes = Crypt32Util.cryptUnprotectData(protectedBytes);
            return Optional.of(new String(clearBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException | LinkageError error) {
            throw new IOException("Windows could not unlock the saved OBS password for this account", error);
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
            if (clearBytes != null) Arrays.fill(clearBytes, (byte) 0);
        }
    }

    public void save(String password) throws IOException {
        if (!isWindows()) throw new IOException("Secure OBS password saving is available on Windows only");
        if (password == null || password.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        byte[] clearBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] protectedBytes = null;
        Path temporary = null;
        try {
            protectedBytes = Crypt32Util.cryptProtectData(clearBytes);
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "obs-password-", ".tmp");
            Files.write(temporary, protectedBytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (RuntimeException | LinkageError error) {
            throw new IOException("Could not protect the OBS password with Windows DPAPI", error);
        } finally {
            Arrays.fill(clearBytes, (byte) 0);
            if (protectedBytes != null) Arrays.fill(protectedBytes, (byte) 0);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }
}
