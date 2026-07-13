package gxopendtu;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * Build timestamp baked into the jar by Maven resource filtering
 * (version.properties, {@code maven.build.timestamp} -- see pom.xml) --
 * shown on the config page so it's obvious which build is actually running
 * after a deploy, without having to compare file hashes or dig through logs.
 *
 * Maven always writes {@code maven.build.timestamp} in UTC ISO-8601
 * (customizing the format via {@code maven.build.timestamp.format} turned
 * out unreliable across Maven versions) -- reformatted here into the
 * server's local time instead of exposing that raw form on the page.
 */
public final class BuildInfo {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final String TIMESTAMP = loadTimestamp();

    private BuildInfo() {}

    /** The build's date/time, formatted in the server's local time zone, or null if unavailable. */
    public static String timestamp() {
        return TIMESTAMP;
    }

    private static String loadTimestamp() {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                return null;
            }
            props.load(in);
            String value = props.getProperty("build.timestamp");
            // Unfiltered placeholder (e.g. compiled straight from an IDE
            // without going through `mvn package`) -- better to show
            // nothing than the literal "${maven.build.timestamp}" string.
            if (value == null || value.startsWith("${")) {
                return null;
            }
            try {
                return DISPLAY_FORMAT.format(Instant.parse(value));
            } catch (DateTimeParseException e) {
                return value; // unexpected format -- still better than hiding it entirely
            }
        } catch (IOException e) {
            return null;
        }
    }
}
