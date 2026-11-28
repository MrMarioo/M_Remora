package pl.mtech.mremora.protocol;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Developer opt-in to another protocol version than the default, e.g. {@code -Dmremora.dev.protocolVersion=3} to try
 * the UDP channel before it is on by default. Read by the agent (highest version offered) and the viewer (version
 * proposed when none is given).
 */
public final class DevProtocolVersion {

    /** The system property; absent means the default version. */
    public static final String PROPERTY = "mremora.dev.protocolVersion";

    private static final Logger LOG = System.getLogger(DevProtocolVersion.class.getName());

    private DevProtocolVersion() {}

    /**
     * The version a {@value #PROPERTY} value selects.
     *
     * @param value          the property value, {@code null} when absent
     * @param defaultVersion returned when {@code value} is {@code null}
     * @param highestVersion highest version the caller implements
     * @throws IllegalArgumentException naming the property and the value, unless it is a whole number
     *     {@code 1..highestVersion} (surrounding whitespace allowed)
     */
    public static int parse(String value, int defaultVersion, int highestVersion) {
        if (value == null) {
            return defaultVersion;
        }
        String message = PROPERTY + " must be a protocol version 1.." + highestVersion + ", was '" + value + "'";
        int version;
        try {
            version = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message, e);
        }
        if (version < 1 || version > highestVersion) {
            throw new IllegalArgumentException(message);
        }
        return version;
    }

    /**
     * {@link #parse} of the system property {@value #PROPERTY}; logs a {@code WARNING} when it is set.
     *
     * @throws IllegalArgumentException for an invalid value
     */
    public static int fromSystemProperty(int defaultVersion, int highestVersion) {
        String value = System.getProperty(PROPERTY);
        int version = parse(value, defaultVersion, highestVersion);
        if (value != null) {
            LOG.log(Level.WARNING, "Developer protocol version active: v{0} ({1})", version, PROPERTY);
        }
        return version;
    }
}
