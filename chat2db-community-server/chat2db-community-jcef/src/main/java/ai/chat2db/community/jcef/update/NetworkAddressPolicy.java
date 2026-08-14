package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Address-level SSRF policy shared by the source and redirect validators.
 */
final class NetworkAddressPolicy {

    private NetworkAddressPolicy() {
    }

    static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true;
        }
        int dots = 0;
        boolean allDigits = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                allDigits = false;
                break;
            }
        }
        return allDigits && dots == 3;
    }

    static void validateHostAddresses(String host) throws IOException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IOException("Unable to resolve host: " + host, e);
        }
        if (addresses.length == 0) {
            throw new IOException("No addresses for host: " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || address.isAnyLocalAddress()
                    || isUniqueLocal(address)) {
                throw new IOException("Host resolves to a forbidden address: " + host);
            }
        }
    }

    private static boolean isUniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length > 0 && ((bytes[0] & 0xFE) == 0xFC);
    }
}
