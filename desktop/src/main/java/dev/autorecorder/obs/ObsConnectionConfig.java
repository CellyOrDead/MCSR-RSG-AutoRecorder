package dev.autorecorder.obs;

/** Non-secret OBS WebSocket address settings remembered between app launches. */
public record ObsConnectionConfig(String host, int port) {
    public ObsConnectionConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("OBS port must be between 1 and 65535");
    }

    public static ObsConnectionConfig defaults() {
        return new ObsConnectionConfig("127.0.0.1", 4455);
    }
}
