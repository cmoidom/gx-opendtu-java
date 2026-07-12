package gxopendtu.grid;

/** Raised on any grid meter read failure -- repeated failures trigger the control loop's fail-safe. */
public class GridMeterUnavailableException extends RuntimeException {

    public GridMeterUnavailableException(String message) {
        super(message);
    }

    public GridMeterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
