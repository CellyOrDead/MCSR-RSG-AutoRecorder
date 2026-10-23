package dev.autorecorder.obs;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Finds an OBS WebSocket listener bound to this computer without scanning the LAN. */
public final class ObsHostDiscovery {
    private static final int CONNECT_TIMEOUT_MILLIS = 350;

    private ObsHostDiscovery() { }

    public static Optional<String> discoverLocalHost(int port) throws SocketException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");

        Set<InetAddress> localAddresses = new LinkedHashSet<>();
        try {
            localAddresses.add(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        } catch (UnknownHostException impossible) {
            throw new AssertionError("IPv4 loopback address is invalid", impossible);
        }
        localAddresses.add(InetAddress.getLoopbackAddress());

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isAnyLocalAddress() && !address.isLoopbackAddress())
                        localAddresses.add(address);
                }
            }
        }

        // Always prefer loopback when OBS is on this PC; it avoids unnecessary LAN routing.
        for (InetAddress address : localAddresses) {
            if (canConnect(address, port)) return Optional.of(address.getHostAddress());
        }
        return Optional.empty();
    }

    private static boolean canConnect(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
