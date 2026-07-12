package gxopendtu.battery;

import gxopendtu.modbus.FakeModbusServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModbusBatterySocTest {

    @Test
    void readSocPctIsUnsigned() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithRegisters(87);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
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
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
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
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
                assertThat(reader.readPowerW()).isEqualTo(-300.0);
            }
        }
    }

    @Test
    void readCurrentAPositiveIsCharging() throws Exception {
        // register 841, scale 10 -> 15.0A
        Map<Integer, int[]> byAddress = Map.of(841, new int[] {150});
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondPerAddress(byAddress::get);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
                assertThat(reader.readCurrentA()).isEqualTo(15.0);
            }
        }
    }

    @Test
    void readCurrentANegativeIsDischarging() throws Exception {
        // -20.0A discharging -> raw -200 as two's complement (65536-200=65336), scale 10
        Map<Integer, int[]> byAddress = Map.of(841, new int[] {65336});
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondPerAddress(byAddress::get);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
                assertThat(reader.readCurrentA()).isEqualTo(-20.0);
            }
        }
    }

    @Test
    void readVoltageVUsesConfiguredUnitId() throws Exception {
        // register 259, scale 100 -> 51.23V, served regardless of which unit id is
        // requested (see FakeModbusServer -- it doesn't model per-unit routing).
        Map<Integer, int[]> byAddress = Map.of(259, new int[] {5123});
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondPerAddress(byAddress::get);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, 225, Duration.ofSeconds(2))) {
                assertThat(reader.readVoltageV()).isEqualTo(51.23);
            }
        }
    }

    @Test
    void readVoltageVThrowsWhenNotConfigured() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithRegisters(5123);
            try (ModbusBatterySoc reader =
                    new ModbusBatterySoc("127.0.0.1", server.port(), 100, null, Duration.ofSeconds(2))) {
                assertThatThrownBy(reader::readVoltageV).isInstanceOf(BatterySocUnavailableException.class);
            }
        }
    }
}
