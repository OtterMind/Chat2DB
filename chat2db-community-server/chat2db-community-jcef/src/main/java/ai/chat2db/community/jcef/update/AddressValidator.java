package ai.chat2db.community.jcef.update;

import java.io.IOException;

/**
 * Validates that a destination host is acceptable before opening a connection.
 */
@FunctionalInterface
interface AddressValidator {

    void validate(String host) throws IOException;

    AddressValidator STRICT = NetworkAddressPolicy::validateHostAddresses;
}
