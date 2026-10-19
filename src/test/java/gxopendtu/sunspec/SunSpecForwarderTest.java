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
                fake, map, ALL, CONTROLLABLE, CONTROLLABLE, NOMINAL, 0.0, 5.0, 5.0, batteryReader, false);
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
    void sendsNothingUntilVictronHasWrittenOnceThenReleasesOnAnExplicitDisable() {
        SunSpecRegisterMap map = registerMap(); // fresh: Ena=0 by default, but Victron hasn't spoken yet
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 100.0, "b", 100.0, "nc", 50.0))
                .withLimitStatus(Map.of());
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();
        forwarder.decisionTick();

        assertThat(fake.relativeCalls).isEmpty();
        assertThat(fake.absoluteCalls).isEmpty();

        setWMaxLim(map, false, 100.0); // Victron really disables the limit
        forwarder.decisionTick();

        assertThat(fake.relativeCalls).containsExactlyInAnyOrder(Map.entry("a", 100.0), Map.entry("b", 100.0));
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
    void enabledLimitCurtailsOnlyTheFirstControllableInverterInCascadeOrder() {
        SunSpecRegisterMap map = registerMap();
        // 50% of the WHOLE nameplate (800+1000+400=2200) -> 1100W raw target, minus the
        // non-controllable inverter's own uncapped 50W actual production -> 1050W. Cascade
        // order a, b: b stays uncapped (its 500W live production is subtracted), a takes the rest --
        // capped at its own 400W production + 100W headroom, since more would restrict nothing.
        setWMaxLim(map, true, 50.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();

        assertThat(fake.relativeCalls).isEmpty();
        assertThat(fake.absoluteCalls).containsExactlyInAnyOrder(Map.entry("a", 500.0), Map.entry("b", 1000.0));
    }

    @Test
    void cascadeOnlyResendsTheMarginalInverterWhenVictronsCapMoves() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 50.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of());
        SunSpecForwarder forwarder = forwarder(fake, map);

        forwarder.decisionTick();
        fake.absoluteCalls.clear();

        setWMaxLim(map, true, 40.0); // 880 - 50 = 830W -> a = 830 - 500 = 330W, b untouched
        forwarder.decisionTick();

        assertThat(fake.absoluteCalls).containsExactly(Map.entry("a", 330.0));
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

        // A real, meaningful change (50% -> 90%) must still go through -- "a" producing more
        // too, otherwise raising its limit further would restrict nothing.
        setWMaxLim(map, true, 90.0);
        fake.withLivePowerW(Map.of("a", 480.0, "b", 500.0, "nc", 50.0));
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
    void respectsWMaxLimPctWhenBatteryIsAtOrAboveFullThreshold() {
        SunSpecRegisterMap map = registerMap();
        setWMaxLim(map, true, 30.0);
        FakeOpenDTUApi fake = new FakeOpenDTUApi()
                .withLivePowerW(Map.of("a", 400.0, "b", 500.0, "nc", 50.0))
                .withLimitStatus(Map.of(
                        "a", new LimitStatus(50.0, 800.0, "Ok"), "b", new LimitStatus(50.0, 1000.0, "Ok")));
        SunSpecForwarder forwarder = forwarder(fake, map, new FixedSocBattery(99.0));

        forwarder.decisionTick();

        // 30% of 2200 - 50 = 610W -> a = 610 - b's 500W = 110W, b uncapped
        assertThat(fake.absoluteCalls).containsExactlyInAnyOrder(Map.entry("a", 110.0), Map.entry("b", 1000.0));
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

        // 30% of 2200 - 50 = 610W -> a = 610 - b's 500W = 110W, b uncapped
        assertThat(fake.absoluteCalls).containsExactlyInAnyOrder(Map.entry("a", 110.0), Map.entry("b", 1000.0));
    }

    @Test
    void resendsAllocationAfterOpenDTURecoveryEvenWhenItIsUnchanged() {
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
        assertThat(fake.relativeCalls).isEmpty();

        fake.failing(false);
        fake.absoluteCalls.clear();
        forwarder.decisionTick();
        assertThat(fake.absoluteCalls).containsExactlyInAnyOrder(Map.entry("a", 800.0), Map.entry("b", 1000.0));
    }

    @Test
    void repeatedOpenDTUFailuresSendNothingSoCurrentLimitsAreKept() {
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
            for (int i = 0; i < 6; i++) {
                forwarder.decisionTick(); // well past the 3-failure threshold
            }
            assertThat(fake.relativeCalls).isEmpty();
            assertThat(fake.absoluteCalls).isEmpty();
        } finally {
            logger.setLevel(previousLevel);
        }
    }
}
