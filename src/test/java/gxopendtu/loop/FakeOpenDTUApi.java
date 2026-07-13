package gxopendtu.loop;

import gxopendtu.opendtu.LimitStatus;
import gxopendtu.opendtu.OpenDTUApi;
import gxopendtu.opendtu.OpenDTUException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stand-in for OpenDTUClient: no real HTTP, just records what would be sent. Mirrors test_dry_run.py's FakeOpenDTUClient. */
final class FakeOpenDTUApi implements OpenDTUApi {

    private final Map<String, Double> livePowerW;
    private final Map<String, LimitStatus> limitStatus;
    private final boolean livePowerError;
    private Map<String, Double> yieldDayWh = Map.of();
    private Map<String, Double> dataAgeS = Map.of();
    final List<Map.Entry<String, Double>> absoluteCalls = new ArrayList<>();
    final List<Map.Entry<String, Double>> relativeCalls = new ArrayList<>();

    FakeOpenDTUApi(Map<String, Double> livePowerW, Map<String, LimitStatus> limitStatus) {
        this(livePowerW, limitStatus, false);
    }

    FakeOpenDTUApi(Map<String, Double> livePowerW, Map<String, LimitStatus> limitStatus, boolean livePowerError) {
        this.livePowerW = livePowerW;
        this.limitStatus = limitStatus;
        this.livePowerError = livePowerError;
    }

    FakeOpenDTUApi withYieldDayWh(Map<String, Double> yieldDayWh) {
        this.yieldDayWh = yieldDayWh;
        return this;
    }

    FakeOpenDTUApi withDataAgeS(Map<String, Double> dataAgeS) {
        this.dataAgeS = dataAgeS;
        return this;
    }

    @Override
    public Map<String, Double> getLivePowerW(Collection<String> serials) {
        if (livePowerError) {
            throw new OpenDTUException("simulated failure");
        }
        return new HashMap<>(livePowerW);
    }

    @Override
    public Map<String, Double> getYieldDayWh(Collection<String> serials) {
        return new HashMap<>(yieldDayWh);
    }

    @Override
    public Map<String, Double> getDataAgeS(Collection<String> serials) {
        return new HashMap<>(dataAgeS);
    }

    @Override
    public Map<String, LimitStatus> getLimitStatus() {
        return new HashMap<>(limitStatus);
    }

    @Override
    public void setAbsoluteLimitW(String serial, double watts) {
        absoluteCalls.add(Map.entry(serial, watts));
    }

    @Override
    public void setRelativeLimitPct(String serial, double percent) {
        relativeCalls.add(Map.entry(serial, percent));
    }
}
