package io.github.jevkit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The jev-java version, written into {@code jev-java.properties} by Maven at build time. */
final class Version {

    static final String VALUE = load();

    private Version() {
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("jev-java.properties")) {
            if (in == null) {
                return "unknown";
            }

            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "unknown");
            // An unfiltered file (e.g. running from an IDE without Maven) still contains the placeholder.
            return version.startsWith("${") ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
