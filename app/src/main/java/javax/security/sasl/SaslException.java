package javax.security.sasl;

import java.io.IOException;

/**
 * A stand-in for the JDK's {@code javax.security.sasl.SaslException}, which Android does not
 * ship at any API level (not even in {@code android.jar}).
 *
 * <p>The bundled PostgreSQL JDBC driver (pgjdbc 42.2.x) shades the ongres SCRAM library, whose
 * {@code ScramException} extends this class. SCRAM-SHA-256 is the default authentication of
 * PostgreSQL 14+, so without this class a SCRAM login fails to load {@code ScramException} and
 * throws {@link NoClassDefFoundError}. Providing the class here lets the driver authenticate.
 *
 * <p>Only the two constructors the shaded {@code ScramException} calls are needed; the cause is
 * kept through {@link IOException}'s own chaining, which {@link #getCause()} exposes.
 */
public class SaslException extends IOException {

    public SaslException() {
        super();
    }

    public SaslException(String detail) {
        super(detail);
    }

    public SaslException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
