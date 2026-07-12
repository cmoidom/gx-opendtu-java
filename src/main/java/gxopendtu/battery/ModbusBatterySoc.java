package gxopendtu.battery;

import gxopendtu.modbus.ModbusConstants;
import gxopendtu.modbus.ModbusException;
import gxopendtu.modbus.ModbusTcpClient;
import gxopendtu.modbus.RegisterCodec;

import java.time.Duration;

/**
 * Reads the Cerbo GX's aggregated battery SOC/power over Modbus TCP.
 *
 * Same fixed system-aggregate unit ID already used for grid power
 * ({@link ModbusConstants#SYSTEM_UNIT_ID}, com.victronenergy.system):
 * register 843 = /Dc/Battery/Soc (uint16, 0-100%, scale 1, no sign
 * conversion needed), register 842 = /Dc/Battery/Power (int16, W, same sign
 * convention as Victron's own UI: positive = charging, negative =
 * discharging).
 *
 * Port of src/battery_soc_modbus.py's ModbusBatterySoc.
 */
public final class ModbusBatterySoc implements BatterySoc, AutoCloseable {

    private static final int SOC_REGISTER = 843;
    private static final int POWER_REGISTER = 842;

    private final int unitId;
    private final ModbusTcpClient client;

    public ModbusBatterySoc(String host, int port, int unitId) {
        this(host, port, unitId, Duration.ofSeconds(5));
    }

    public ModbusBatterySoc(String host, int port, int unitId, Duration timeout) {
        this.unitId = unitId;
        this.client = new ModbusTcpClient(host, port, timeout);
    }

    private int readRegister(int register) {
        try {
            int[] registers = client.readHoldingRegisters(unitId, register, 1);
            return registers[0];
        } catch (ModbusException e) {
            throw new BatterySocUnavailableException("Modbus read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double readSocPct() {
        return readRegister(SOC_REGISTER);
    }

    @Override
    public double readPowerW() {
        return RegisterCodec.toSigned16(readRegister(POWER_REGISTER));
    }

    @Override
    public void close() {
        client.close();
    }
}
