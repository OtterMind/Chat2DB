package ai.chat2db.community.domain.api.model.datasource;

import lombok.Data;
import lombok.ToString;


@Data
@ToString(exclude = {"clientPrivateKeyPem", "clientKeyPassword", "keyStoreBytes", "keyStorePassword"})
public class SSLInfo {

    /**
     * TLS verification mode. See {@code MySqlTlsMode}. Blank/null is treated as DISABLED.
     */
    private String tlsMode;

    /**
     * CA certificate PEM content (one-way TLS). Public material, stored cleartext.
     */
    private String caPem;

    /**
     * Client certificate PEM content (mutual TLS). Public material, stored cleartext.
     */
    private String clientCertPem;

    /**
     * PKCS#8 private key PEM content (mutual TLS). Secret — encrypted at rest.
     */
    private String clientPrivateKeyPem;

    /**
     * Private key password (mutual TLS). Secret — encrypted at rest.
     */
    private String clientKeyPassword;

    /**
     * Optional keystore type override (PKCS12/JKS) when a pre-built keystore is supplied
     * instead of PEM. Blank when PEM is used.
     */
    private String keyStoreType;

    /**
     * Optional keystore bytes, Base64-encoded (mutual TLS). Secret — encrypted at rest.
     */
    private String keyStoreBytes;

    /**
     * Keystore password. Secret — encrypted at rest.
     */
    private String keyStorePassword;
}
