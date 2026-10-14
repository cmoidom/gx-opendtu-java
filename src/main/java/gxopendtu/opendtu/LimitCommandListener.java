package gxopendtu.opendtu;

/**
 * Notified by {@link OpenDTUClient} after OpenDTU accepted a limit command
 * (HTTP success -- not the inverter's own RF acknowledgement, which arrives
 * later via {@link LimitStatus#acknowledged()}). Lets the dashboard show when
 * each inverter was last sent a limit, whichever module sent it (ControlLoop
 * or the SunSpec forwarder, each with their own client).
 */
@FunctionalInterface
public interface LimitCommandListener {

    /** {@code unit} is "W" for an absolute limit, "%" for a relative one. */
    void limitSent(String serial, double value, String unit);
}
