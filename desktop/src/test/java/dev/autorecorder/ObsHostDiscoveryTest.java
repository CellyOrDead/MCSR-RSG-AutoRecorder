package dev.autorecorder;

import dev.autorecorder.obs.ObsHostDiscovery;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class ObsHostDiscoveryTest {
    @Test void discoversAListenerOnThisComputer() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals("127.0.0.1", ObsHostDiscovery.discoverLocalHost(listener.getLocalPort()).orElseThrow());
        }
    }

    @Test void rejectsAnInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> ObsHostDiscovery.discoverLocalHost(0));
        assertThrows(IllegalArgumentException.class, () -> ObsHostDiscovery.discoverLocalHost(65536));
    }
}
