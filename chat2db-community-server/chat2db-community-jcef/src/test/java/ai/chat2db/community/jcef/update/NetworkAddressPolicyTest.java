package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAddressPolicyTest {

    @Test
    void detectsIpv4Literal() {
        assertTrue(NetworkAddressPolicy.isIpLiteral("140.82.121.4"));
    }

    @Test
    void detectsIpv6Literal() {
        assertTrue(NetworkAddressPolicy.isIpLiteral("::1"));
        assertTrue(NetworkAddressPolicy.isIpLiteral("2001:db8::1"));
    }

    @Test
    void detectsDomainNameIsNotLiteral() {
        assertFalse(NetworkAddressPolicy.isIpLiteral("github.com"));
        assertFalse(NetworkAddressPolicy.isIpLiteral("objects.githubusercontent.com"));
    }

    @Test
    void rejectsLoopback() {
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("127.0.0.1"));
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("localhost"));
    }

    @Test
    void rejectsPrivateIpv4() {
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("10.0.0.1"));
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("172.16.0.1"));
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("192.168.1.1"));
    }

    @Test
    void rejectsLinkLocal() {
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("169.254.1.1"));
    }

    @Test
    void rejectsMulticast() {
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("224.0.0.1"));
    }

    @Test
    void rejectsUnspecified() {
        assertThrows(IOException.class, () -> NetworkAddressPolicy.validateHostAddresses("0.0.0.0"));
    }
}
