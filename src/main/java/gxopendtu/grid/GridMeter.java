package gxopendtu.grid;

/**
 * Reads instantaneous grid power and cumulative import/export energy.
 *
 * Sign convention: positive = grid import (soutirage), negative = grid
 * export (injection) -- see ARCHITECTURE.md. Single-phase installation only:
 * a single power value, no per-phase breakdown.
 */
public interface GridMeter {

    double readGridPowerW();

    EnergyReading readEnergyKwh();

    /** Cumulative totals since the meter's own counter was last reset, not a rate. */
    record EnergyReading(double fromNetKwh, double toNetKwh) {}
}
