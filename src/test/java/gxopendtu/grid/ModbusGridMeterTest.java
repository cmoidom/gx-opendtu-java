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
                    new ModbusGridMeter("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
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
                    new ModbusGridMeter("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
                GridMeter.EnergyReading reading = meter.readEnergyKwh();
                assertThat(reading.fromNetKwh()).isEqualTo(1234.56);
                assertThat(reading.toNetKwh()).isEqualTo(5.0);
            }
        }
    }

}
