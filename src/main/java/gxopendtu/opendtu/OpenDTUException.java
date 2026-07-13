package gxopendtu.opendtu;

/** Raised on any OpenDTU HTTP request failure (network error, timeout, non-2xx status, bad JSON). */
public class OpenDTUException extends RuntimeException {

    private static final long serialVersionUID = -1274470492481986735L;

    public OpenDTUException(String message) {
        super(message);
    }

    public OpenDTUException(String message, Throwable cause) {
        super(message, cause);
    }
}
