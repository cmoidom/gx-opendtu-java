package gxopendtu.battery;

import gxopendtu.modbus.ModbusConstants;
import gxopendtu.modbus.ModbusException;
import gxopendtu.modbus.ModbusTcpClient;
import gxopendtu.modbus.RegisterCodec;

import java.time.Duration;

/**
 * Reads the Cerbo GX's aggregated battery SOC/power/current over Modbus TCP,
 * plus battery voltage from the battery monitor's own service.
 *
 * SOC/power/current all live on the fixed system-aggregate unit ID already
 * used for grid power ({@link ModbusConstants#SYSTEM_UNIT_ID},
 * com.victronenergy.system): register 843 = /Dc/Battery/Soc (uint16,
 * 0-100%, scale 1, no sign conversion needed), register 842 =
 * /Dc/Battery/Power (int16, W), register 841 = Battery Current (int16,
 * scale 10 -> A) -- same sign convention as Victron's own UI: positive =
 * charging, negative = discharging. Confirmed against Victron's official
 * CCGX Modbus-TCP register list.
 *
 * Voltage (register 259, uint16, scale 100 -> V, path /Dc/0/Voltage) lives
 * on the battery monitor's OWN service (com.victronenergy.battery), NOT the
 * system aggregate -- unit ID {@link #VOLTAGE_UNIT_ID} (225), this install's
 * fixed VRM device instance for that service. Not user-configurable
 * (deliberately -- this value is specific to this hardware/install, not a
 * per-deployment setting): if a future install needs a different value,
 * change this constant.
 *
 * Port of src/battery_soc_modbus.py's ModbusBatterySoc, extended with
 * current and voltage.
 */
public final class ModbusBatterySoc implements BatterySoc, AutoCloseable {

    private static final int SOC_REGISTER = 843;
    private static final int POWER_REGISTER = 842;
    private static final int CURRENT_REGISTER = 841;
    private static final int VOLTAGE_REGISTER = 259;
    private static final int VOLTAGE_UNIT_ID = 225;

    private final ModbusTcpClient client;

    public ModbusBatterySoc(String host, int port) {
        this(host, port, Duration.ofSeconds(5));
    }

    public ModbusBatterySoc(String host, int port, Duration timeout) {
        this.client = new ModbusTcpClient(host, port, timeout);
    }

    private int readRegister(int unit, int register) {
        try {
            int[] registers = client.readHoldingRegisters(unit, register, 1);
            return registers[0];
        } catch (ModbusException e) {
            throw new BatterySocUnavailableException("Modbus read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double readSocPct() {
        return readRegister(ModbusConstants.SYSTEM_UNIT_ID, SOC_REGISTER);
    }

    @Override
    public double readPowerW() {
        return RegisterCodec.toSigned16(readRegister(ModbusConstants.SYSTEM_UNIT_ID, POWER_REGISTER));
    }

    @Override
    public double readCurrentA() {
        return RegisterCodec.toSigned16(readRegister(ModbusConstants.SYSTEM_UNIT_ID, CURRENT_REGISTER)) / 10.0;
    }

    @Override
    public double readVoltageV() {
        return readRegister(VOLTAGE_UNIT_ID, VOLTAGE_REGISTER) / 100.0;
    }

    @Override
    public void close() {
        client.close();
    }
}
