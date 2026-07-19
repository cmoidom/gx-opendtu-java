package gxopendtu.sunspec;

/** Unchecked, thrown when the SunSpec proxy's TCP server fails to bind/start. */
public final class SunSpecProxyException extends RuntimeException {

    public SunSpecProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
