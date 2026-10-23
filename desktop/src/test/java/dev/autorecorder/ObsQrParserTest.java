package dev.autorecorder;

import dev.autorecorder.obs.ObsQrParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObsQrParserTest {
    @Test void extractsHostPortAndPassword(){
        var c=ObsQrParser.parse("obsws://192.168.1.20:4455/my%2Bpass%40word");
        assertEquals("192.168.1.20",c.host());assertEquals(4455,c.port());assertEquals("my+pass@word",c.password());assertTrue(c.authenticationRequired());
    }
    @Test void supportsQrWithoutAuthentication(){
        var c=ObsQrParser.parse("obsws://localhost:4455/");assertEquals("localhost",c.host());assertFalse(c.authenticationRequired());
    }
    @Test void rejectsSecureOrWrongSchemeWithoutEchoingSecret(){
        assertThrows(IllegalArgumentException.class,()->ObsQrParser.parse("obswss://host:4455/secret"));
        assertThrows(IllegalArgumentException.class,()->ObsQrParser.parse("https://host:4455/secret"));
    }
}
