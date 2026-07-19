package gxopendtu.sunspec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, observation-only view of the SunSpec proxy spike, for the
 * /internal debug page -- mirrors the read-only-introspection role
 * {@code state.InternalStatus} plays for the main control loop, but kept
 * completely separate since this is an independent, opt-in subsystem with no
 * relationship to the decision cycle.
 *
 * Never read by anything that acts on it: {@link SunSpecTcpServer} stores
 * whatever Venus OS writes here purely for display, and never forwards it to
 * OpenDTU/the real inverters -- see the class javadoc on
 * {@code SunSpecRegisterMap} for why.
 */
public final class SunSpecProxyState {

    private final ReentrantLock lock = new ReentrantLock();
    private double lastLivePowerW;
    private double lastLivePowerUpdatedAtEpochS;
    private double lastLifetimeEnergyWh;
    private Double lastWriteWMaxLimPct;
    private Boolean lastWriteWMaxLimEnabled;
    private Boolean lastWriteConn;
    private Double lastWriteAtEpochS;
    private String lastWriteRemoteAddress;
    private int tcpConnectionCount;

    public void recordLivePowerW(double aggregateW, double nowEpochS) {
        lock.lock();
        try {
            lastLivePowerW = aggregateW;
            lastLivePowerUpdatedAtEpochS = nowEpochS;
        } finally {
            lock.unlock();
        }
    }

    public void recordLifetimeEnergyWh(double aggregateWh) {
        lock.lock();
        try {
            lastLifetimeEnergyWh = aggregateWh;
        } finally {
            lock.unlock();
        }
    }

    /** Called whenever a write touching Conn/WMaxLimPct/WMaxLim_Ena is received -- purely observational. */
    public void recordControlWrite(
            double wMaxLimPct, boolean wMaxLimEnabled, boolean conn, String remoteAddress, double nowEpochS) {
        lock.lock();
        try {
            lastWriteWMaxLimPct = wMaxLimPct;
            lastWriteWMaxLimEnabled = wMaxLimEnabled;
            lastWriteConn = conn;
            lastWriteRemoteAddress = remoteAddress;
            lastWriteAtEpochS = nowEpochS;
        } finally {
            lock.unlock();
        }
    }

    public void recordConnectionOpened() {
        lock.lock();
        try {
            tcpConnectionCount++;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> snapshot() {
        lock.lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("live_power_w", lastLivePowerW);
            m.put("live_power_updated_at", lastLivePowerUpdatedAtEpochS);
            m.put("lifetime_energy_wh", lastLifetimeEnergyWh);
            m.put("last_write_wmaxlimpct", lastWriteWMaxLimPct);
            m.put("last_write_wmaxlim_enabled", lastWriteWMaxLimEnabled);
            m.put("last_write_conn", lastWriteConn);
            m.put("last_write_remote_address", lastWriteRemoteAddress);
            m.put("last_write_at", lastWriteAtEpochS);
            m.put("tcp_connection_count", tcpConnectionCount);
            return m;
        } finally {
            lock.unlock();
        }
    }
}
