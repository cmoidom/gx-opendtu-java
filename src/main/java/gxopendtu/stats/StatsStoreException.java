package gxopendtu.stats;

/** Raised when the long-term stats SQLite database can't be opened. Persisting a single sample never throws -- see StatsStore. */
public class StatsStoreException extends RuntimeException {

    private static final long serialVersionUID = 7546278932952446038L;

    public StatsStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
