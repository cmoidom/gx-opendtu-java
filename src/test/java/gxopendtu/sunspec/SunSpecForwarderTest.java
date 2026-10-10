package gxopendtu.sunspec;

import gxopendtu.battery.BatterySoc;
import gxopendtu.battery.BatterySocUnavailableException;
import gxopendtu.opendtu.LimitStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class SunSpecForwarderTest {

    private static final List<String> ALL = List.of("a", "b", "nc");
    private static final List<String> CONTROLLABLE = List.of("a", "b");
    private static final Map<String, Double> NOMINAL = Map.of("a", 800.0, "b", 1000.0, "nc", 400.0);

    private static final class FixedSocBattery implements BatterySoc {
        private double socPct;

        FixedSocBattery(double socPct) {
            this.socPct = socPct;
        }

        void setSocPct(double socPct) {
            this.socPct = socPct;
        }

        @Override
        public double readSocPct() {
            return socPct;
        }

        @Override
        public double readPowerW() {
            return 0.0;
        }

        @Override
        public double readCurrentA() {
            return 0.0;
        }

        @Override
        public double readVoltageV() {
            return 0.0;
        }
    }

    private static final class FailingSocBattery implements BatterySoc {
        @Override
        public double readSocPct() {
            throw new BatterySocUnavailableException("simulated failure");
        }

        @Override
        public double readPowerW() {
            return 0.0;
        }

        @Override
        public double readCurrentA() {
            return 0.0;
        }

        @Override
        public double readVoltageV() {
            return 0.0;
        }
    }

    private SunSpecForwarder forwarder(FakeOpenDTUApi fake, SunSpecRegisterMap map) {
        return forwarder(fake, map, null);
    }

    private SunSpecForwarder forwarder(FakeOpenDTUApi fake, SunSpecRegisterMap map, BatterySoc batteryReader) {
        return new SunSpecForwarder(
                fake, map, ALL, CONTROLLABLE, NOMINAL, 50.0, 0.0, 5.0, 30.0, 5.0, batteryReader, false);
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
    void ignoresWMaxLimPctWhenBatteryIsBelowFullThreshold() {
        SunSpecRegisterMap map = registerMap();
        // 30% would be 660W raw target; ignored because the battery isn't full -> 100%
        // (2150W after subtracting nc's actual) instead, capped at "a"+"b"'s combined
        // nominal capacity (1800W) since that raw target exceeds what they can produce.
        setWMaxLim(map, true, 30.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map, new FixedSocBattery(66.0));

        forwarder.decisionTick();

        double total = fake.absoluteCalls.stream().mapToDouble(Map.Entry::getValue).sum();
        assertThat(total).isCloseTo(1800.0, org.assertj.core.data.Offset.offset(0.01)); // a+b nominal ceiling
    }

    @Test
    void ignoredBypassSendsFullNominalEvenWithADegradedCapacityCeiling() {
        // A previously-observed shortfall (persisted across enough cycles)
        // lowers "a"'s capacity ceiling while Victron's cap is still trusted
        // (battery full). Once the battery drops below the full threshold,
        // the bypass must send "a" its full nominal power directly -- not
        // the stale, degraded ceiling -- since nothing genuinely restricts
        // production anymore (2026-07-26 fix: the ceiling used to still gate
        // the allocation even while the Victron cap itself was ignored).
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 100.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 100.0, "b", 1000.0, "nc", 0.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(100.0, 800.0, "Ok"), "b", new LimitStatus(100.0, 1000.0, "Ok")));
        FixedSocBattery battery = new FixedSocBattery(99.0);
        SunSpecForwarder forwarder = forwarder(fake, map, battery);

        // 3 cycles of persistent, acknowledged underperformance on "a" (100W
        // actual against an 800W ask) degrade its ceiling; a 4th tick is what
        // actually sends that newly-degraded (lower) ceiling.
        forwarder.decisionTick();
        forwarder.decisionTick();
        forwarder.decisionTick();
        forwarder.decisionTick();

        double lastSentToA = fake.absoluteCalls.stream()
                .filter(e -> e.getKey().equals("a"))
                .mapToDouble(Map.Entry::getValue)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(lastSentToA).isLessThan(800.0); // ceiling did degrade, confirming the setup

        battery.setSocPct(80.0); // battery drops below the full threshold -> bypass engages
        forwarder.decisionTick();

        double finalSentToA = fake.absoluteCalls.stream()
                .filter(e -> e.getKey().equals("a"))
                .mapToDouble(Map.Entry::getValue)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(finalSentToA).isEqualTo(800.0); // full nominal, not the degraded ceiling
    }

    @Test
    void respectsWMaxLimPctWhenBatteryIsAtOrAboveFullThreshold() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 30.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map, new FixedSocBattery(99.0));

        forwarder.decisionTick();

        double total = fake.absoluteCalls.stream().mapToDouble(Map.Entry::getValue).sum();
        assertThat(total).isCloseTo(610.0, org.assertj.core.data.Offset.offset(0.01)); // 30% of 2200 - 50
    }

    @Test
    void respectsWMaxLimPctWhenBatterySocReadFails() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 30.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map, new FailingSocBattery());

        Logger logger = Logger.getLogger(SunSpecForwarder.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            forwarder.decisionTick();
        } finally {
            logger.setLevel(previousLevel);
        }

        double total = fake.absoluteCalls.stream().mapToDouble(Map.Entry::getValue).sum();
        assertThat(total).isCloseTo(610.0, org.assertj.core.data.Offset.offset(0.01)); // 30% of 2200 - 50
    }

    @Test
    void resendsAllocationAfterFailsafeEvenWhenItIsUnchanged() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 30.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        // SOC bypass: constant nameplate allocation every tick, the exact live case.
        SunSpecForwarder forwarder = forwarder(fake, map, new FixedSocBattery(37.0));

        forwarder.decisionTick();
        assertThat(fake.absoluteCalls).containsExactlyInAnyOrder(Map.entry("a", 800.0), Map.entry("b", 1000.0));

        Logger logger = Logger.getLogger(SunSpecForwarder.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            fake.failing(true);
            forwarder.decisionTick();
            forwarder.decisionTick();
            forwarder.decisionTick();
        } finally {
            logger.setLevel(previousLevel);
        }
        assertThat(fake.relativeCalls).containsExactlyInAnyOrder(Map.entry("a", 0.0), Map.entry("b", 0.0));

        fake.failing(false);
        fake.absoluteCalls.clear();
        forwarder.decisionTick();
        assertThat(fake.absoluteCalls).containsExactlyInAnyOrder(Map.entry("a", 800.0), Map.entry("b", 1000.0));
    }

    @Test
    void repeatedOpenDTUFailuresTriggerFailsafeCurtailment() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 100.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi().failing(true);
        SunSpecForwarder forwarder = forwarder(fake, map);

        // decisionTick logs each simulated failure at WARNING with the exception attached (useful in
        // real operation, but just noise for this deliberately-triggered test) -- muted for the duration.
        Logger logger = Logger.getLogger(SunSpecForwarder.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            forwarder.decisionTick();
            forwarder.decisionTick();
            assertThat(fake.relativeCalls).isEmpty(); // not yet at the failure threshold

            forwarder.decisionTick();
            assertThat(fake.relativeCalls).containsExactlyInAnyOrder(Map.entry("a", 0.0), Map.entry("b", 0.0));
        } finally {
            logger.setLevel(previousLevel);
        }
    }
}
