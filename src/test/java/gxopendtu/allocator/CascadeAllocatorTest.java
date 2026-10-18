package gxopendtu.allocator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeAllocatorTest {

    // Shaped like the real install: one big inverter first, then smaller ones.
    private static final List<String> ORDER = List.of("big", "mid", "small");
    private static final Map<String, Double> NOMINAL = Map.of("big", 2000.0, "mid", 1000.0, "small", 400.0);

    /** No data_age known: the early hand-back never triggers, only the floor/nominal rules apply. */
    private static Map<String, Double> alloc(CascadeAllocator allocator, double targetW, Map<String, Double> live) {
        return allocator.allocate(targetW, live, Map.of(), 0.0);
    }

    /** Switching logic alone: no release margin, no ramp (both tested separately below). */
    private static CascadeAllocator allocator() {
        return new CascadeAllocator(ORDER, NOMINAL, 5.0, 0.0, Double.POSITIVE_INFINITY); // floors: 100 / 50 / 20 W
    }

    @Test
    void onlyTheFirstInverterIsCurtailedWhileTheOthersCoverLessThanTheTarget() {
        CascadeAllocator allocator = allocator();

        Map<String, Double> allocation = alloc(allocator, 900.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        assertThat(allocation).containsExactly(
                Map.entry("big", 500.0), Map.entry("mid", 1000.0), Map.entry("small", 400.0));
        assertThat(allocator.marginalIndex()).isZero();
    }

    @Test
    void movesToTheNextInverterOnceTheFirstWouldGoBelowItsFloor() {
        CascadeAllocator allocator = allocator();

        // big at its 100W floor, small uncapped at 100W -> mid gets 300 - 100 - 100 = 100W
        Map<String, Double> allocation = alloc(allocator, 300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        assertThat(allocation).containsExactly(
                Map.entry("big", 100.0), Map.entry("mid", 100.0), Map.entry("small", 400.0));
        assertThat(allocator.marginalIndex()).isEqualTo(1);
    }

    @Test
    void everyInverterAtItsFloorWhenTheTargetIsBelowTheSumOfFloors() {
        CascadeAllocator allocator = allocator();

        Map<String, Double> allocation = alloc(allocator, 50.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        assertThat(allocation).containsExactly(
                Map.entry("big", 100.0), Map.entry("mid", 50.0), Map.entry("small", 20.0));
        assertThat(allocator.marginalIndex()).isEqualTo(2);
    }

    @Test
    void handsBackToThePreviousInverterOnlyOnceTheMarginalOneWouldExceedItsNominalPower() {
        CascadeAllocator allocator = allocator();
        alloc(allocator, 300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0)); // mid marginal

        // mid would need 900 - 100 - 100 = 700W: still within its 1000W, stays marginal
        Map<String, Double> stillMid = alloc(allocator, 900.0, Map.of("big", 100.0, "mid", 300.0, "small", 100.0));
        assertThat(allocator.marginalIndex()).isEqualTo(1);
        assertThat(stillMid.get("mid")).isEqualTo(700.0);

        // mid would need 1300 - 100 - 100 = 1100W > 1000W: big resumes, mid uncapped
        Map<String, Double> backToBig = alloc(allocator, 1300.0, Map.of("big", 100.0, "mid", 900.0, "small", 100.0));
        assertThat(allocator.marginalIndex()).isZero();
        assertThat(backToBig).containsExactly(
                Map.entry("big", 300.0), Map.entry("mid", 1000.0), Map.entry("small", 400.0));
    }

    @Test
    void aChangeOfTargetMovesOnlyTheMarginalInverter() {
        CascadeAllocator allocator = allocator();
        Map<String, Double> live = Map.of("big", 700.0, "mid", 300.0, "small", 100.0);

        Map<String, Double> first = alloc(allocator, 300.0, live);
        Map<String, Double> second = alloc(allocator, 400.0, live);

        assertThat(second.get("big")).isEqualTo(first.get("big"));
        assertThat(second.get("small")).isEqualTo(first.get("small"));
        assertThat(second.get("mid")).isEqualTo(first.get("mid") + 100.0);
    }

    @Test
    void theMarginalLimitIgnoresChangesSmallerThanTheDeadband() {
        CascadeAllocator allocator = allocator();
        Map<String, Double> live = Map.of("big", 700.0, "mid", 300.0, "small", 100.0);
        alloc(allocator, 300.0, live); // mid marginal at 100W

        assertThat(alloc(allocator, 360.0, live).get("mid")).isEqualTo(100.0); // +60W: kept
        assertThat(alloc(allocator, 250.0, live).get("mid")).isEqualTo(100.0); // -50W: kept
        assertThat(alloc(allocator, 380.0, live).get("mid")).isEqualTo(180.0); // +80W: moves
    }

    @Test
    void resetStartsOverFromTheFirstInverter() {
        CascadeAllocator allocator = allocator();
        alloc(allocator, 50.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        allocator.reset();

        assertThat(allocator.marginalIndex()).isZero();
    }

    @Test
    void doesNotLoopForeverWithAHighFloorNextToASmallInverter() {
        // 50% floor of a 2000W inverter (1000W) dwarfs the next one's nominal (400W).
        CascadeAllocator allocator = new CascadeAllocator(List.of("big", "small"), NOMINAL, 50.0);

        Map<String, Double> allocation = alloc(allocator, 900.0, Map.of("big", 1500.0, "small", 0.0));

        assertThat(allocation).containsKeys("big", "small");
    }

    @Test
    void handsBackOnceTwoFreshReadingsShowTheMarginalInverterFarBelowItsLimit() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0); // mid marginal
        // mid then gets 700W at t=0 but only has 40W of sun (the live 2026-10-14 case)
        Map<String, Double> live = Map.of("big", 100.0, "mid", 40.0, "small", 100.0);
        allocator.allocate(900.0, live, Map.of("mid", 30.0), 0.0); // reading from before any limit: ignored

        // OpenDTU refreshes mid every ~18s; the same reading is re-read on the ticks in between
        allocator.allocate(900.0, live, Map.of("mid", 2.0), 10.0); // reading at t=8: 1st underuse
        allocator.allocate(900.0, live, Map.of("mid", 7.0), 15.0); // same reading (t=8): not recounted
        assertThat(allocator.marginalIndex()).isEqualTo(1);
        Map<String, Double> handedBack = allocator.allocate(900.0, live, Map.of("mid", 1.0), 27.0); // t=26: 2nd

        assertThat(allocator.marginalIndex()).isZero();
        // mid uncapped again, big gets what mid and small really produce left of the target
        assertThat(handedBack).containsExactly(
                Map.entry("big", 760.0), Map.entry("mid", 1000.0), Map.entry("small", 400.0));
    }

    @Test
    void aReadingTakenBeforeTheLimitIncreaseSettledIsJudgedAgainstTheOlderLowerLimit() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0); // mid at 100W
        // limit raised to 700W at t=20; the next readings of mid (t=22, t=40) still show ~95W
        Map<String, Double> lagging = Map.of("big", 100.0, "mid", 95.0, "small", 100.0);
        allocator.allocate(900.0, lagging, Map.of(), 20.0);
        allocator.allocate(900.0, lagging, Map.of("mid", 3.0), 25.0); // t=22 < 20 + settle: vs 100W -> fine
        allocator.allocate(900.0, lagging, Map.of("mid", 0.0), 40.0); // t=40: vs 700W -> 1st underuse

        assertThat(allocator.marginalIndex()).isEqualTo(1); // a single late reading is not enough
    }

    @Test
    void aMarginalInverterThatFollowsItsLimitIsNeverHandedBack() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0); // mid 100W

        for (int i = 1; i <= 10; i++) {
            allocator.allocate(300.0, Map.of("big", 100.0, "mid", 95.0, "small", 100.0), Map.of("mid", 0.0), i * 20.0);
        }

        assertThat(allocator.marginalIndex()).isEqualTo(1);
    }

    @Test
    void underuseCountRestartsWhenTheMarginalInverterCatchesUp() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0);
        allocator.allocate(900.0, Map.of("big", 100.0, "mid", 40.0, "small", 100.0), Map.of(), 0.0); // 700W

        allocator.allocate(900.0, Map.of("big", 100.0, "mid", 40.0, "small", 100.0), Map.of("mid", 0.0), 20.0);
        allocator.allocate(900.0, Map.of("big", 100.0, "mid", 700.0, "small", 100.0), Map.of("mid", 0.0), 40.0);
        allocator.allocate(900.0, Map.of("big", 100.0, "mid", 40.0, "small", 100.0), Map.of("mid", 0.0), 60.0);

        assertThat(allocator.marginalIndex()).isEqualTo(1);
    }

    @Test
    void releasesOneInverterPerCallAndAssumesNominalPowerUntilAReadingShowsItsRealProduction() {
        CascadeAllocator allocator = allocator();
        // all at floor except small: target 50W
        allocator.allocate(50.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0);
        assertThat(allocator.marginalIndex()).isEqualTo(2);
        // readings at their floors (taken at t=20, after the floors settled)
        Map<String, Double> atFloors = Map.of("big", 100.0, "mid", 50.0, "small", 20.0);

        // Victron lets 1400W through: small first (one step), not everybody at once
        Map<String, Double> first = allocator.allocate(1400.0, atFloors, Map.of("big", 0.0, "mid", 0.0, "small", 0.0), 20.0);
        assertThat(allocator.marginalIndex()).isEqualTo(1);
        assertThat(first.get("big")).isEqualTo(100.0);

        // small's 20W reading predates its release: its 400W nominal is assumed, not 20W
        Map<String, Double> second = allocator.allocate(1400.0, atFloors, Map.of("big", 0.0, "mid", 0.0, "small", 2.0), 22.0);
        assertThat(allocator.marginalIndex()).isEqualTo(1);
        assertThat(second.get("mid")).isEqualTo(900.0); // 1400 - big's floor 100 - small's assumed 400
    }

    @Test
    void aReleasedInverterThatTurnsOutToProduceLittleIsCountedAtItsRealReadingOnceAvailable() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(50.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0);
        allocator.allocate(600.0, Map.of("big", 100.0, "mid", 50.0, "small", 20.0), Map.of("small", 0.0), 20.0); // small released

        // fresh reading of small taken at t=40 (uncapped since t=20): only 30W of sun
        Map<String, Double> allocation = allocator.allocate(
                600.0, Map.of("big", 100.0, "mid", 50.0, "small", 30.0), Map.of("small", 0.0, "mid", 0.0), 40.0);

        assertThat(allocation.get("mid")).isEqualTo(470.0); // 600 - big's floor 100 - small's real 30
    }

    @Test
    void anInverterOpenDTUHasLostIsTakenOutOfTheCascadeAndItsProductionSubtracted() {
        CascadeAllocator allocator = allocator();
        // big unreachable for 8 min, still producing its last-known 677W (the live 2026-10-17 case)
        Map<String, Double> live = Map.of("big", 677.0, "mid", 300.0, "small", 100.0);
        Map<String, Double> ages = Map.of("big", 480.0, "mid", 3.0, "small", 5.0);

        Map<String, Double> allocation = allocator.allocate(900.0, live, ages, 600.0);

        // mid takes the curtailment instead: 900 - 677 - small's 100 = 123W
        assertThat(allocation.get("mid")).isEqualTo(123.0);
        assertThat(allocation.get("small")).isEqualTo(400.0);
        assertThat(allocation.get("big")).isEqualTo(2000.0); // never limited yet: keeps its (nominal) limit
    }

    @Test
    void thePreviousInverterOnlyResumesBeyondTheReleaseMarginAndTheMarginalLimitRampsUp() {
        CascadeAllocator allocator = new CascadeAllocator(ORDER, NOMINAL, 5.0); // production margin + ramp
        Map<String, Double> live = Map.of("big", 100.0, "mid", 50.0, "small", 300.0);
        allocator.allocate(250.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0), Map.of(), 0.0); // mid at 50W
        assertThat(allocator.marginalIndex()).isEqualTo(1);

        // mid would need 900W: it climbs by 100W per call instead of jumping straight there
        assertThat(allocator.allocate(1300.0, live, Map.of(), 5.0).get("mid")).isEqualTo(150.0);
        assertThat(allocator.allocate(1300.0, live, Map.of(), 10.0).get("mid")).isEqualTo(250.0);

        // mid would need 1050W: above its 1000W nominal but within the 100W margin -> stays marginal
        allocator.allocate(1450.0, live, Map.of(), 15.0);
        assertThat(allocator.marginalIndex()).isEqualTo(1);
        // 1150W needed: beyond the margin -> big resumes, from its floor, ramping too
        Map<String, Double> released = allocator.allocate(1550.0, live, Map.of(), 20.0);
        assertThat(allocator.marginalIndex()).isZero();
        assertThat(released.get("big")).isEqualTo(200.0);
    }
}
