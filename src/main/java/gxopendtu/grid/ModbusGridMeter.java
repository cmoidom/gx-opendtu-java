package gxopendtu.grid;

import gxopendtu.modbus.ModbusConstants;
import gxopendtu.modbus.ModbusException;
import gxopendtu.modbus.ModbusTcpClient;
import gxopendtu.modbus.RegisterCodec;

import java.time.Duration;

/**
 * Reads instantaneous grid power and cumulative import/export energy from a
 * Cerbo GX over Modbus TCP -- for running the controller off-device (a
 * separate Linux VM) instead of directly on Venus OS.
 *
 * Uses the Cerbo GX's fixed system-aggregate Modbus unit ID
 * ({@link ModbusConstants#SYSTEM_UNIT_ID}, com.victronenergy.system) for
 * everything, including the energy counters -- not configurable, and not a
 * per-install setting: specific to this deployment's hardware, same
 * reasoning as {@code ModbusBatterySoc#VOLTAGE_UNIT_ID}. Register 820 = Grid
 * L1 active power (int16, W, 1:1 scale, negative = export). Single-phase
 * installation: only register 820 (L1) is read.
 *
 * Energy counters (registers 2634/2636, "Total Energy from/to net", uint32,
 * scale 100 -> kWh) technically belong to the grid meter's OWN
 * com.victronenergy.grid Modbus service (a different unit ID from the system
 * aggregate on some installs), but on this one it happens to share the
 * aggregate's unit ID, so no separate constant is needed. Deliberately not
 * the per-phase 2603 register (uint16, wraps at 655.35 kWh) -- this project
 * is single-phase, so "L1" and "Total" are the same physical quantity, and
 * uint32 avoids the wraparound.
 *
 * Port of src/grid_meter_modbus.py's ModbusGridMeter.
 */
public final class ModbusGridMeter implements GridMeter, AutoCloseable {

    private static final int GRID_L1_POWER_REGISTER = 820;
    private static final int ENERGY_FROM_NET_REGISTER = 2634;
    private static final int ENERGY_TO_NET_REGISTER = 2636;

    private final ModbusTcpClient client;

    public ModbusGridMeter(String host, int port) {
        this(host, port, Duration.ofSeconds(5));
    }

    public ModbusGridMeter(String host, int port, Duration timeout) {
        this.client = new ModbusTcpClient(host, port, timeout);
    }

    @Override
    public double readGridPowerW() {
        try {
            int[] registers = client.readHoldingRegisters(ModbusConstants.SYSTEM_UNIT_ID, GRID_L1_POWER_REGISTER, 1);
            return RegisterCodec.toSigned16(registers[0]);
        } catch (ModbusException e) {
            throw new GridMeterUnavailableException("Modbus read failed: " + e.getMessage(), e);
        }
    }

    private double readUint32Kwh(int register) {
        try {
            int[] registers = client.readHoldingRegisters(ModbusConstants.SYSTEM_UNIT_ID, register, 2);
            long combined = RegisterCodec.combineBigEndianUint32(registers[0], registers[1]);
            return combined / 100.0;
        } catch (ModbusException e) {
            throw new GridMeterUnavailableException("Modbus read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public EnergyReading readEnergyKwh() {
        double fromKwh = readUint32Kwh(ENERGY_FROM_NET_REGISTER);
        double toKwh = readUint32Kwh(ENERGY_TO_NET_REGISTER);
        return new EnergyReading(fromKwh, toKwh);
    }

    @Override
    public void close() {
        client.close();
    }
}
