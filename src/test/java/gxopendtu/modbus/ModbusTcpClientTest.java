package gxopendtu.modbus;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModbusTcpClientTest {

    @Test
    void readsRegistersFromRealServerRoundTrip() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithRegisters(0x0001, 0x86A0); // e.g. a 32-bit energy counter split across two registers
            ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2));
            try {
                int[] registers = client.readHoldingRegisters(100, 2634, 2);
                assertThat(registers).containsExactly(0x0001, 0x86A0);
            } finally {
                client.close();
            }
        }
    }

    @Test
    void singleRegisterReadRoundTrip() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithRegisters(65036); // -500 as unsigned int16 on the wire
            ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2));
            try {
                int[] registers = client.readHoldingRegisters(100, 820, 1);
                assertThat(registers).containsExactly(65036);
                assertThat(RegisterCodec.toSigned16(registers[0])).isEqualTo(-500);
            } finally {
                client.close();
            }
        }
    }

    @Test
    void exceptionResponseRaisesModbusException() throws Exception {
        try (FakeModbusServer server = new FakeModbusServer()) {
            server.respondWithException(0x02); // illegal data address
            ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2));
            try {
                assertThatThrownBy(() -> client.readHoldingRegisters(100, 999, 1))
                        .isInstanceOf(ModbusException.class)
                        .hasMessageContaining("exception");
            } finally {
                client.close();
            }
        }
    }

    @Test
    void connectionRefusedRaisesModbusException() {
        // Nothing listening on this port.
        ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", 1, Duration.ofMillis(500));
        assertThatThrownBy(() -> client.readHoldingRegisters(100, 820, 1)).isInstanceOf(ModbusException.class);
    }
}
