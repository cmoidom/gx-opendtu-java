package gxopendtu.grid;

import gxopendtu.modbus.FakeModbusServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModbusGridMeterTest {

    @Test
    void readGridPowerWConvertsTwosComplementExport() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithRegisters(65036); // -500W (exporting), unsigned on the wire
            try (ModbusGridMeter meter =
                    new ModbusGridMeter("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
                assertThat(meter.readGridPowerW()).isEqualTo(-500.0);
            }
        }
    }

    @Test
    void readEnergyKwhCombinesHighAndLowWordsPerRegister() throws Exception {
        // 123456 raw -> 1234.56 kWh; high=1, low=57920 -> (1<<16)|57920 = 123456
        Map<Integer, int[]> byAddress = Map.of(
                2634, new int[] {1, 57920},
                2636, new int[] {0, 500});
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondPerAddress(byAddress::get);
            try (ModbusGridMeter meter =
                    new ModbusGridMeter("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
                GridMeter.EnergyReading reading = meter.readEnergyKwh();
                assertThat(reading.fromNetKwh()).isEqualTo(1234.56);
                assertThat(reading.toNetKwh()).isEqualTo(5.0);
            }
        }
    }

    @Test
    void energyUnitIdDefaultsToUnitId() {
        ModbusGridMeter meter = new ModbusGridMeter("192.168.1.50", 502, 42, null);
        assertThat(meter.energyUnitId()).isEqualTo(42);
    }

    @Test
    void energyUnitIdCanBeOverridden() {
        ModbusGridMeter meter = new ModbusGridMeter("192.168.1.50", 502, 100, 30);
        assertThat(meter.energyUnitId()).isEqualTo(30);
    }
}
