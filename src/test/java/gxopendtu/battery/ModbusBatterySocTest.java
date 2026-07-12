package gxopendtu.battery;

import gxopendtu.modbus.FakeModbusServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModbusBatterySocTest {

    @Test
    void readSocPctIsUnsigned() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithRegisters(87);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, Duration.ofSeconds(2))) {
                assertThat(reader.readSocPct()).isEqualTo(87.0);
            }
        }
    }

    @Test
    void readPowerWPositiveIsCharging() throws Exception {
        Map<Integer, int[]> byAddress = Map.of(842, new int[] {250});
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondPerAddress(byAddress::get);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, Duration.ofSeconds(2))) {
                assertThat(reader.readPowerW()).isEqualTo(250.0);
            }
        }
    }

    @Test
    void readPowerWNegativeIsDischarging() throws Exception {
        // -300W (discharging) is stored as two's complement (65536 - 300) on the wire.
        Map<Integer, int[]> byAddress = Map.of(842, new int[] {65236});
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondPerAddress(byAddress::get);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, Duration.ofSeconds(2))) {
                assertThat(reader.readPowerW()).isEqualTo(-300.0);
            }
        }
    }
}
