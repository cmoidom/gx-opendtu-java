package gxopendtu.state;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Sticky AUTO/ON/OFF override for which branch of the control loop runs
 * (SOC-hysteresis-driven PI curtailment vs charge-priority release).
 *
 * Distinct from {@link ManualOverride}: this doesn't bypass the PI or auto-
 * expire -- its purpose is to let a user un-stick a wrong hysteresis state
 * or deliberately hold a mode for as long as needed. ON/OFF directly set
 * BatteryFullHysteresis.active, so switching back to AUTO resumes
 * hysteresis-driven behaviour from wherever that left it, not from scratch.
 *
 * Port of src/manual_override.py's InjectionModeOverride.
 */
public final class InjectionModeOverride {

    public enum Mode {
        AUTO,
        ON,
        OFF
    }

    private final ReentrantLock lock = new ReentrantLock();
    private Mode mode = Mode.AUTO;

    public void setMode(Mode mode) {
        lock.lock();
        try {
            this.mode = mode;
        } finally {
            lock.unlock();
        }
    }

    /** @throws IllegalArgumentException if raw isn't one of AUTO/ON/OFF (case-sensitive). */
    public void setMode(String raw) {
        setMode(Mode.valueOf(raw));
    }

    public Mode getMode() {
        lock.lock();
        try {
            return mode;
        } finally {
            lock.unlock();
        }
    }
}
