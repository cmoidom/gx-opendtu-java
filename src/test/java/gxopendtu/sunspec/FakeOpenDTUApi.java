package gxopendtu.sunspec;

import gxopendtu.opendtu.LimitStatus;
import gxopendtu.opendtu.OpenDTUApi;
import gxopendtu.opendtu.OpenDTUException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal OpenDTUApi test double for the SunSpec package -- gxopendtu.loop.FakeOpenDTUApi is package-private there. */
final class FakeOpenDTUApi implements OpenDTUApi {

    private Map<String, Double> livePowerW = Map.of();
    private Map<String, LimitStatus> limitStatus = Map.of();
    private boolean failing;
    final List<Map.Entry<String, Double>> absoluteCalls = new ArrayList<>();
    final List<Map.Entry<String, Double>> relativeCalls = new ArrayList<>();

    FakeOpenDTUApi withLivePowerW(Map<String, Double> livePowerW) {
        this.livePowerW = livePowerW;
        return this;
    }

    FakeOpenDTUApi withLimitStatus(Map<String, LimitStatus> limitStatus) {
        this.limitStatus = limitStatus;
        return this;
    }

    FakeOpenDTUApi failing(boolean failing) {
        this.failing = failing;
        return this;
    }

    @Override
    public Map<String, Double> getLivePowerW(Collection<String> serials) {
        if (failing) {
            throw new OpenDTUException("simulated failure");
        }
        return new HashMap<>(livePowerW);
    }

    @Override
    public Map<String, Double> getYieldDayWh(Collection<String> serials) {
        return Map.of();
    }

    @Override
    public Map<String, Double> getYieldTotalWh(Collection<String> serials) {
        return Map.of();
    }

    @Override
    public Map<String, Double> getAcVoltageV(Collection<String> serials) {
        return Map.of();
    }

    @Override
    public Map<String, Double> getAcCurrentA(Collection<String> serials) {
        return Map.of();
    }

    @Override
    public String getFirmwareVersion() {
        return "unknown";
    }

    @Override
    public Map<String, Double> getDataAgeS(Collection<String> serials) {
        return Map.of();
    }

    @Override
    public Map<String, LimitStatus> getLimitStatus() {
        if (failing) {
            throw new OpenDTUException("simulated failure");
        }
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
