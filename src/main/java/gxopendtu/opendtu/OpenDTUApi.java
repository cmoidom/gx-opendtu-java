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

    Map<String, LimitStatus> getLimitStatus();

    void setAbsoluteLimitW(String serial, double watts);

    void setRelativeLimitPct(String serial, double percent);
}
