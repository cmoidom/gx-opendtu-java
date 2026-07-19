package gxopendtu.sunspec;

import gxopendtu.state.LiveState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class SunSpecPollerTest {

    @Test
    void recordsWMaxLimPctAsWattsRegardlessOfOpenDTUReachability() {
        SunSpecRegisterMap map = new SunSpecRegisterMap("Fronius", "gx-opendtu-java", "SN1", 2000.0);
        map.writeRegisters(SunSpecRegisterMap.M123_WMAXLIMPCT, new int[] {550}); // 55.0% (SF=-1)
        LiveState liveState = new LiveState(10);
        FakeOpenDTUApi fake = new FakeOpenDTUApi().failing(true); // OpenDTU unreachable

        SunSpecPoller poller =
                new SunSpecPoller(fake, List.of("a"), map, new SunSpecProxyState(), 2.0, liveState, 2000.0);

        // pollOnce logs each simulated OpenDTU failure at WARNING with the exception attached (useful in
        // real operation, but just noise for this deliberately-unreachable test) -- muted for the duration.
        Logger logger = Logger.getLogger(SunSpecPoller.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            poller.pollOnce();
        } finally {
            logger.setLevel(previousLevel);
        }

        liveState.recordGrid(0.0, 0.0);
        Map<String, Object> sample = liveState.snapshotSince(0.0).latest();
        // 55.0% of 2000W = 1100W -- recorded even though every OpenDTU read below fails.
        assertThat(sample.get("sunspec_target_w")).isEqualTo(1100.0);
    }
}
