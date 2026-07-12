package gxopendtu;

import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Configures java.util.logging to approximate Python's logging.basicConfig format used by the reference project. */
final class LoggingSetup {

    private LoggingSetup() {}

    static void configure() {
        Logger root = Logger.getLogger("");
        for (var handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return String.format(
                        "%1$tF %1$tT %2$s %3$s%n",
                        new Date(record.getMillis()), record.getLevel(), formatMessage(record));
            }
        });
        root.addHandler(handler);
        root.setLevel(Level.INFO);
    }
}
