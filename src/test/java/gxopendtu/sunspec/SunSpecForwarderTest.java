package gxopendtu.sunspec;

import gxopendtu.opendtu.LimitStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SunSpecForwarderTest {

    private static final List<String> ALL = List.of("a", "b", "nc");
    private static final List<String> CONTROLLABLE = List.of("a", "b");
    private static final Map<String, Double> NOMINAL = Map.of("a", 800.0, "b", 1000.0, "nc", 400.0);

    private SunSpecForwarder forwarder(FakeOpenDTUApi fake, SunSpecRegisterMap map) {
        return new SunSpecForwarder(fake, map, ALL, CONTROLLABLE, NOMINAL, 50.0, 0.0, 5.0, 30.0, 5.0);
    }

    private static SunSpecRegisterMap registerMap() {
        return new SunSpecRegisterMap("Fronius", "gx-opendtu-java", "SN1", 2200.0);
    }

    private static void setWMaxLim(SunSpecRegisterMap map, boolean enabled, double pct) {
        map.writeRegisters(SunSpecRegisterMap.M123_WMAXLIM_ENA, new int[] {enabled ? 1 : 0});
        map.writeRegisters(SunSpecRegisterMap.M123_WMAXLIMPCT, new int[] {(int) Math.round(pct * 10)});
    }

    @Test
    void limitDisabledReleasesControllableInvertersToFullPowerOnce() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, false, 100.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 100.0, "b", 100.0, "nc", 50.0))
                .withLimitStatus(Map.of());
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();
        forwarder.decisionTick();
        forwarder.decisionTick();

        assertThat(fake.relativeCalls).containsExactlyInAnyOrder(Map.entry("a", 100.0), Map.entry("b", 100.0));
        assertThat(fake.absoluteCalls).isEmpty();
    }

    @Test
    void disconnectedIsTreatedAsDisabledEvenIfEnaIsSet() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 50.0);
        map.writeRegisters(SunSpecRegisterMap.M123_CONN, new int[] {0}); // Conn = DISCONNECT
        FakeOpenDTUApi fake = new FakeOpenDTUApi().withLivePowerW(Map.of()).withLimitStatus(Map.of());
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();

        assertThat(fake.relativeCalls).containsExactlyInAnyOrder(Map.entry("a", 100.0), Map.entry("b", 100.0));
        assertThat(fake.absoluteCalls).isEmpty();
    }

    @Test
    void enabledLimitDistributesWMaxLimPctAcrossControllableInvertersOnly() {
        SunSpecRegisterMap map = registerMap();
        // 50% of the WHOLE nameplate (800+1000+400=2200) -> 1100W raw target, minus the
        // non-controllable inverter's own uncapped 50W actual production -> 1050W for water-fill.
        setWMaxLim(map, true, 50.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();

        assertThat(fake.relativeCalls).isEmpty();
        double total = fake.absoluteCalls.stream().mapToDouble(Map.Entry::getValue).sum();
        assertThat(total).isCloseTo(1050.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(fake.absoluteCalls).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void doesNotResendWhenAllocationBarelyChangesButDoesWhenItMovesEnough() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 50.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();
        int firstSendCount = fake.absoluteCalls.size();
        assertThat(firstSendCount).isEqualTo(2); // both "a" and "b" sent on the first tick

        // WMaxLimPct unchanged -> identical allocation next tick -> no resend at all.
        forwarder.decisionTick();
        assertThat(fake.absoluteCalls).hasSize(firstSendCount);

        // A real, meaningful change (50% -> 90%) must still go through.
        setWMaxLim(map, true, 90.0);
        forwarder.decisionTick();
        assertThat(fake.absoluteCalls).hasSizeGreaterThan(firstSendCount);
    }

    @Test
    void repeatedOpenDTUFailuresTriggerFailsafeCurtailment() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 100.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi().failing(true);
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();
        forwarder.decisionTick();
        assertThat(fake.relativeCalls).isEmpty(); // not yet at the failure threshold

        forwarder.decisionTick();
        assertThat(fake.relativeCalls).containsExactlyInAnyOrder(Map.entry("a", 0.0), Map.entry("b", 0.0));
    }
}
