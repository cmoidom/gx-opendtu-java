package gxopendtu.stats;

/** Raised when the long-term stats SQLite database can't be opened. Persisting a single sample never throws -- see StatsStore. */
public class StatsStoreException extends RuntimeException {

    public StatsStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
