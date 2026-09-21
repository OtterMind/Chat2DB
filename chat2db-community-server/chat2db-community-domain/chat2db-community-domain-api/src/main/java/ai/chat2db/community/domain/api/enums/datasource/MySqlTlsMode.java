package ai.chat2db.community.domain.api.enums.datasource;

import ai.chat2db.community.tools.exception.BusinessException;
import lombok.Getter;


@Getter
public enum MySqlTlsMode {

    /**
     * No TLS. The default when the field is blank.
     */
    DISABLED,

    /**
     * Encrypt the connection but do not verify the server certificate.
     */
    REQUIRED,

    /**
     * Encrypt and verify the server certificate against the supplied CA.
     */
    VERIFY_CA,

    /**
     * Like VERIFY_CA, additionally verifying the certificate hostname.
     */
    VERIFY_IDENTITY;

    /**
     * Parse a mode code, case-insensitive. Blank/null resolves to {@link #DISABLED}.
     * An unrecognized value fails loudly instead of silently downgrading to plaintext.
     *
     * @param code the raw mode string from {@code SSLInfo.tlsMode}
     * @return the matched mode, or {@link #DISABLED} when blank
     */
    public static MySqlTlsMode fromString(String code) {
        if (code == null || code.isBlank()) {
            return DISABLED;
        }
        for (MySqlTlsMode mode : values()) {
            if (mode.name().equalsIgnoreCase(code)) {
                return mode;
            }
        }
        throw new BusinessException("mysql.tls.unsupportedMode");
    }
}
