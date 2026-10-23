package dev.autorecorder.obs;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Parses OBS WebSocket Connect Info QR payloads without ever including the password in errors. */
public final class ObsQrParser {
    private ObsQrParser() {}
    public record Connection(String host, int port, String password, boolean authenticationRequired) {}

    public static Connection parse(String payload) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("QR code is empty");
        final URI uri;
        try { uri = URI.create(payload.trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("QR code does not contain valid OBS connection data"); }
        String scheme = uri.getScheme();
        if (!"obsws".equalsIgnoreCase(scheme) && !"obswss".equalsIgnoreCase(scheme))
            throw new IllegalArgumentException("This is not an OBS WebSocket QR code");
        if ("obswss".equalsIgnoreCase(scheme))
            throw new IllegalArgumentException("Secure OBS QR (obswss) is not supported yet; use the local ws QR from OBS");
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port < 1 || port > 65535 || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("QR code is missing a valid host or port");
        String raw = uri.getRawPath();
        String password = "";
        if (raw != null && !raw.isEmpty() && !"/".equals(raw)) {
            String encoded = raw.startsWith("/") ? raw.substring(1) : raw;
            try { password = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("QR password encoding is invalid"); }
            if (password.contains("/")) throw new IllegalArgumentException("QR password path is invalid");
        }
        return new Connection(host, port, password, !password.isEmpty());
    }
}
