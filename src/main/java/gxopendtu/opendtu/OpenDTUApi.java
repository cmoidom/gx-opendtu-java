package gxopendtu.opendtu;

import java.util.Collection;
import java.util.Map;

/**
 * The subset of OpenDTUClient's operations the control loop needs. Exists so
 * tests can supply a fake implementation without a real HTTP client -- the
 * Java equivalent of the Python reference project's duck-typed
 * FakeOpenDTUClient (tests/test_dry_run.py), formalized as an interface.
 */
public interface OpenDTUApi {

    Map<String, Double> getLivePowerW(Collection<String> serials);

    /** {serial: today's cumulative AC yield, in Wh} -- resets to 0 at local midnight on the inverter itself. */
    Map<String, Double> getYieldDayWh(Collection<String> serials);

    /** {serial: lifetime cumulative AC yield, in Wh} -- never resets, unlike {@link #getYieldDayWh}. */
    Map<String, Double> getYieldTotalWh(Collection<String> serials);

    /** {serial: measured AC output voltage, in V}. */
    Map<String, Double> getAcVoltageV(Collection<String> serials);

    /** {serial: measured AC output current, in A}. */
    Map<String, Double> getAcCurrentA(Collection<String> serials);

    /**
     * {serial: seconds since OpenDTU's own last successful RF read of that
     * inverter} -- OpenDTU polls inverters one at a time over a single RF
     * module, so with several inverters configured, any given one's cached
     * telemetry can be tens of seconds old regardless of how often we poll
     * OpenDTU's HTTP API. Used to avoid treating a stale reading as fresh
     * evidence of a capacity limit (see control.CapacityEstimator).
     */
    Map<String, Double> getDataAgeS(Collection<String> serials);

    Map<String, LimitStatus> getLimitStatus();

    void setAbsoluteLimitW(String serial, double watts);

    void setRelativeLimitPct(String serial, double percent);
}
