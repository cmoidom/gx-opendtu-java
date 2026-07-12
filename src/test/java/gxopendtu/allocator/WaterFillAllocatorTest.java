package gxopendtu.allocator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static gxopendtu.allocator.WaterFillAllocator.waterFillAllocate;
import static org.assertj.core.api.Assertions.assertThat;

class WaterFillAllocatorTest {

    @Test
    void equalSplitWhenAllHaveHeadroom() {
        Map<String, Double> allocation =
                waterFillAllocate(600.0, List.of("a", "b", "c"), Map.of("a", 600.0, "b", 600.0, "c", 600.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 200.0, "b", 200.0, "c", 200.0));
    }

    @Test
    void saturatedInverterGetsCappedAndRestRedistributed() {
        // "a" can only give 50W; the other 550W target should be split between b and c.
        Map<String, Double> allocation =
                waterFillAllocate(600.0, List.of("a", "b", "c"), Map.of("a", 50.0, "b", 600.0, "c", 600.0));
        assertThat(allocation.get("a")).isEqualTo(50.0);
        assertThat(allocation.get("b")).isEqualTo(275.0);
        assertThat(allocation.get("c")).isEqualTo(275.0);
    }

    @Test
    void cascadingSaturation() {
        Map<String, Double> allocation =
                waterFillAllocate(300.0, List.of("a", "b", "c"), Map.of("a", 10.0, "b", 20.0, "c", 1000.0));
        assertThat(allocation.get("a")).isEqualTo(10.0);
        assertThat(allocation.get("b")).isEqualTo(20.0);
        assertThat(allocation.get("c")).isEqualTo(270.0);
    }

    @Test
    void zeroTargetGivesZeroToAll() {
        Map<String, Double> allocation = waterFillAllocate(0.0, List.of("a", "b"), Map.of("a", 100.0, "b", 100.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 0.0, "b", 0.0));
    }

    @Test
    void missingCapacityEstimateTreatedAsUnlimited() {
        Map<String, Double> allocation = waterFillAllocate(100.0, List.of("a", "b"), Map.of("a", 10.0));
        assertThat(allocation.get("a")).isEqualTo(10.0);
        assertThat(allocation.get("b")).isEqualTo(90.0);
    }

    @Test
    void negativeTargetClampedToZero() {
        Map<String, Double> allocation = waterFillAllocate(-50.0, List.of("a", "b"), Map.of("a", 100.0, "b", 100.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 0.0, "b", 0.0));
    }

    @Test
    void minInverterPctFloorsALowNonzeroShare() {
        // 10W total split two ways is 5W each; a 10% floor of 600W nominal is 60W.
        Map<String, Double> allocation = waterFillAllocate(
                10.0, List.of("a", "b"), Map.of("a", 600.0, "b", 600.0), 10.0, Map.of("a", 600.0, "b", 600.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 60.0, "b", 60.0));
    }

    @Test
    void minInverterPctAppliesEvenWhenTargetIsZero() {
        Map<String, Double> allocation = waterFillAllocate(
                0.0, List.of("a", "b"), Map.of("a", 600.0, "b", 600.0), 10.0, Map.of("a", 600.0, "b", 600.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 60.0, "b", 60.0));
    }

    @Test
    void minInverterPctStaysZeroWithNoRealCapacity() {
        // capacityEstimates == 0 means genuinely irradiance-limited to zero.
        Map<String, Double> allocation = waterFillAllocate(
                0.0, List.of("a", "b"), Map.of("a", 0.0, "b", 600.0), 10.0, Map.of("a", 600.0, "b", 600.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 0.0, "b", 60.0));
    }

    @Test
    void minInverterPctNeverExceedsCapacityCeiling() {
        // Floor would be 10% of 600 = 60W, but this inverter is shaded down to 20W.
        Map<String, Double> allocation =
                waterFillAllocate(5.0, List.of("a"), Map.of("a", 20.0), 10.0, Map.of("a", 600.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 20.0));
    }

    @Test
    void minInverterPctDisabledByDefault() {
        Map<String, Double> allocation = waterFillAllocate(10.0, List.of("a", "b"), Map.of("a", 600.0, "b", 600.0));
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 5.0, "b", 5.0));
    }
}
