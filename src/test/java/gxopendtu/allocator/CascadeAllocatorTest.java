package gxopendtu.allocator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeAllocatorTest {

    // Shaped like the real install: one big inverter first, then smaller ones.
    private static final List<String> ORDER = List.of("big", "mid", "small");
    private static final Map<String, Double> NOMINAL = Map.of("big", 2000.0, "mid", 1000.0, "small", 400.0);

    private static CascadeAllocator allocator() {
        return new CascadeAllocator(ORDER, NOMINAL, 5.0); // floors: 100 / 50 / 20 W
    }

    @Test
    void onlyTheFirstInverterIsCurtailedWhileTheOthersCoverLessThanTheTarget() {
        CascadeAllocator allocator = allocator();

        Map<String, Double> allocation = allocator.allocate(900.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        assertThat(allocation).containsExactly(
                Map.entry("big", 500.0), Map.entry("mid", 1000.0), Map.entry("small", 400.0));
        assertThat(allocator.marginalIndex()).isZero();
    }

    @Test
    void movesToTheNextInverterOnceTheFirstWouldGoBelowItsFloor() {
        CascadeAllocator allocator = allocator();

        // big at its 100W floor, small uncapped at 100W -> mid gets 300 - 100 - 100 = 100W
        Map<String, Double> allocation = allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        assertThat(allocation).containsExactly(
                Map.entry("big", 100.0), Map.entry("mid", 100.0), Map.entry("small", 400.0));
        assertThat(allocator.marginalIndex()).isEqualTo(1);
    }

    @Test
    void everyInverterAtItsFloorWhenTheTargetIsBelowTheSumOfFloors() {
        CascadeAllocator allocator = allocator();

        Map<String, Double> allocation = allocator.allocate(50.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        assertThat(allocation).containsExactly(
                Map.entry("big", 100.0), Map.entry("mid", 50.0), Map.entry("small", 20.0));
        assertThat(allocator.marginalIndex()).isEqualTo(2);
    }

    @Test
    void handsBackToThePreviousInverterOnlyOnceTheMarginalOneWouldExceedItsNominalPower() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0)); // mid marginal

        // mid would need 900 - 100 - 100 = 700W: still within its 1000W, stays marginal
        Map<String, Double> stillMid = allocator.allocate(900.0, Map.of("big", 100.0, "mid", 300.0, "small", 100.0));
        assertThat(allocator.marginalIndex()).isEqualTo(1);
        assertThat(stillMid.get("mid")).isEqualTo(700.0);

        // mid would need 1300 - 100 - 100 = 1100W > 1000W: big resumes, mid uncapped
        Map<String, Double> backToBig = allocator.allocate(1300.0, Map.of("big", 100.0, "mid", 900.0, "small", 100.0));
        assertThat(allocator.marginalIndex()).isZero();
        assertThat(backToBig).containsExactly(
                Map.entry("big", 300.0), Map.entry("mid", 1000.0), Map.entry("small", 400.0));
    }

    @Test
    void aSmallChangeOfTargetMovesOnlyTheMarginalInverter() {
        CascadeAllocator allocator = allocator();
        Map<String, Double> live = Map.of("big", 700.0, "mid", 300.0, "small", 100.0);

        Map<String, Double> first = allocator.allocate(300.0, live);
        Map<String, Double> second = allocator.allocate(350.0, live);

        assertThat(second.get("big")).isEqualTo(first.get("big"));
        assertThat(second.get("small")).isEqualTo(first.get("small"));
        assertThat(second.get("mid")).isEqualTo(first.get("mid") + 50.0);
    }

    @Test
    void resetStartsOverFromTheFirstInverter() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(50.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));

        allocator.reset();

        assertThat(allocator.marginalIndex()).isZero();
    }

    @Test
    void doesNotLoopForeverWithAHighFloorNextToASmallInverter() {
        // 50% floor of a 2000W inverter (1000W) dwarfs the next one's nominal (400W).
        CascadeAllocator allocator = new CascadeAllocator(List.of("big", "small"), NOMINAL, 50.0);

        Map<String, Double> allocation = allocator.allocate(900.0, Map.of("big", 1500.0, "small", 0.0));

        assertThat(allocation).containsKeys("big", "small");
    }

    @Test
    void handsBackEarlyWhenTheMarginalInverterKeepsProducingFarBelowItsLimit() {
        CascadeAllocator allocator = allocator();
        // big at its floor, mid marginal with a 700W limit but only 40W of sun (the live 2026-10-14 case)
        Map<String, Double> live = Map.of("big", 100.0, "mid", 40.0, "small", 100.0);
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));
        assertThat(allocator.marginalIndex()).isEqualTo(1);

        for (int i = 0; i < CascadeAllocator.HANDBACK_TICKS - 1; i++) {
            allocator.allocate(900.0, live);
            assertThat(allocator.marginalIndex()).isEqualTo(1); // not yet: could still be RF lag
        }
        Map<String, Double> handedBack = allocator.allocate(900.0, live);

        assertThat(allocator.marginalIndex()).isZero();
        // mid uncapped again, big gets what mid and small really produce left of the target
        assertThat(handedBack).containsExactly(
                Map.entry("big", 760.0), Map.entry("mid", 1000.0), Map.entry("small", 400.0));
    }

    @Test
    void aMarginalInverterThatFollowsItsLimitIsNeverHandedBack() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0)); // mid gets 100W

        for (int i = 0; i < 10; i++) {
            allocator.allocate(300.0, Map.of("big", 100.0, "mid", 95.0, "small", 100.0));
        }

        assertThat(allocator.marginalIndex()).isEqualTo(1);
    }

    @Test
    void underuseCountRestartsWhenTheMarginalInverterCatchesUp() {
        CascadeAllocator allocator = allocator();
        allocator.allocate(300.0, Map.of("big", 700.0, "mid", 300.0, "small", 100.0));
        Map<String, Double> lagging = Map.of("big", 100.0, "mid", 40.0, "small", 100.0);

        for (int i = 0; i < CascadeAllocator.HANDBACK_TICKS - 1; i++) {
            allocator.allocate(900.0, lagging);
        }
        allocator.allocate(900.0, Map.of("big", 100.0, "mid", 700.0, "small", 100.0)); // caught up
        allocator.allocate(900.0, lagging);

        assertThat(allocator.marginalIndex()).isEqualTo(1);
    }
}
