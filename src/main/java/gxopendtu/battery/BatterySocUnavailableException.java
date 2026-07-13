package gxopendtu.battery;

/**
 * Raised on any battery SOC/power read failure. The control loop's safe
 * default on this is to keep injection control ACTIVE (never release
 * curtailment unsupervised) -- see AGENTS.md.
 */
public class BatterySocUnavailableException extends RuntimeException {

    private static final long serialVersionUID = -7749985016686640920L;

    public BatterySocUnavailableException(String message) {
        super(message);
    }

    public BatterySocUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
