package gxopendtu.sunspec;

import gxopendtu.opendtu.OpenDTUApi;
import gxopendtu.opendtu.OpenDTUException;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background loop feeding real aggregate live power from OpenDTU into
 * {@link SunSpecRegisterMap} (Model 101's {@code W}) and {@link SunSpecProxyState}
 * (for the /internal page) -- decoupled from however often Venus OS itself
 * polls the Modbus TCP server, same reasoning as {@code loop.ControlLoop}
 * running its own cadence independent of the dashboard's poll rate.
 *
 * A plain sleep loop on its own daemon thread, not a ScheduledExecutorService
 * -- matches this codebase's existing bias toward the simplest thing that
 * works (see ControlLoop.run itself).
 */
public final class SunSpecPoller {

    private static final Logger LOG = Logger.getLogger(SunSpecPoller.class.getName());

    private final OpenDTUApi client;
    private final Collection<String> serials;
    private final SunSpecRegisterMap registerMap;
    private final SunSpecProxyState state;
    private final long intervalMs;
    private volatile boolean running;

    public SunSpecPoller(
            OpenDTUApi client,
            Collection<String> serials,
            SunSpecRegisterMap registerMap,
            SunSpecProxyState state,
            double intervalS) {
        this.client = client;
        this.serials = serials;
        this.registerMap = registerMap;
        this.state = state;
        this.intervalMs = Math.round(intervalS * 1000);
    }

    public void start() {
        running = true;
        Thread thread = new Thread(this::loop, "sunspec-proxy-poller");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    private void loop() {
        while (running) {
            pollOnce();
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() {
        try {
            double aggregateW = client.getLivePowerW(serials).values().stream().mapToDouble(Double::doubleValue).sum();
            registerMap.setLivePowerW(aggregateW);
            state.recordLivePowerW(aggregateW, System.currentTimeMillis() / 1000.0);
        } catch (OpenDTUException e) {
            LOG.log(Level.WARNING, "[spike SunSpec] lecture OpenDTU echouee (aucun effet sur la regulation reelle)", e);
        }
    }
}
