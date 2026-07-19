package gxopendtu.sunspec;

import gxopendtu.modbus.ModbusTcpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SunSpecTcpServer} over a real loopback socket. FC3 reads
 * are verified with the project's own {@code modbus.ModbusTcpClient} (it
 * already speaks FC3 correctly against the real grid meter) -- proof the
 * server's read side is wire-compatible with the exact same client code the
 * rest of this project trusts. FC6/FC16 writes have no existing client to
 * reuse (this project never writes Modbus elsewhere), so those are
 * hand-framed here.
 */
class SunSpecTcpServerTest {

    private SunSpecTcpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private SunSpecTcpServer startServer(double totalNominalPowerW) {
        SunSpecRegisterMap registerMap = new SunSpecRegisterMap("Fronius", "gx-opendtu-java", "SN1", totalNominalPowerW);
        SunSpecProxyState state = new SunSpecProxyState();
        SunSpecTcpServer s = new SunSpecTcpServer(0, registerMap, state);
        s.start();
        return s;
    }

    @Test
    void readsSunSMarkerViaTheProjectsOwnModbusClient() throws Exception {
        server = startServer(1000.0);
        try (ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
            int[] regs = client.readHoldingRegisters(1, SunSpecRegisterMap.SUNSPEC_BASE, 2);
            assertThat(regs).containsExactly(0x5375, 0x6e53);
        }
    }

    @Test
    void readsModel120NameplateWithRealNominalPower() throws Exception {
        server = startServer(2500.0);
        try (ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
            int[] regs = client.readHoldingRegisters(1, SunSpecRegisterMap.SUNSPEC_BASE + 122, 4);
            assertThat(regs[0]).isEqualTo(120); // ID
            assertThat(regs[2]).isEqualTo(4); // DERTyp = PV
            assertThat(regs[3]).isEqualTo(2500); // WRtg
        }
    }

    @Test
    void writeSingleRegisterIsReflectedOnSubsequentRead() throws IOException {
        server = startServer(1000.0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // FC6: write WMaxLimPct = 550 (55.0%, SF=-1) at its absolute address.
            int address = SunSpecRegisterMap.SUNSPEC_BASE + SunSpecRegisterMap.M123_WMAXLIMPCT;
            writeSingleRegister(out, in, address, 550);

            try (ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
                int[] readBack = client.readHoldingRegisters(1, address, 1);
                assertThat(readBack).containsExactly(550);
            }
        }
    }

    @Test
    void writeMultipleRegistersIsReflectedOnSubsequentRead() throws IOException {
        server = startServer(1000.0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            int address = SunSpecRegisterMap.SUNSPEC_BASE + SunSpecRegisterMap.M123_CONN;
            writeMultipleRegisters(out, in, address, new int[] {0, 1000}); // Conn=0, WMaxLimPct=100.0%

            try (ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
                assertThat(client.readHoldingRegisters(1, address, 2)).containsExactly(0, 1000);
            }
        }
    }

    @Test
    void readOutOfRangeReturnsModbusException() {
        server = startServer(1000.0);
        try (ModbusTcpClient client = new ModbusTcpClient("127.0.0.1", server.port(), Duration.ofSeconds(2))) {
            assertThat(catchThrowable(() -> client.readHoldingRegisters(1, SunSpecRegisterMap.SUNSPEC_BASE, 9999)))
                    .isNotNull();
        }
    }

    private static Throwable catchThrowable(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void writeSingleRegister(DataOutputStream out, DataInputStream in, int address, int value)
            throws IOException {
        out.writeShort(1); // transaction id
        out.writeShort(0); // protocol id
        out.writeShort(6); // unitId + functionCode + address(2) + value(2)
        out.writeByte(1); // unit id
        out.writeByte(0x06);
        out.writeShort(address);
        out.writeShort(value);
        out.flush();

        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedByte();
        int functionCode = in.readUnsignedByte();
        assertThat(functionCode).isEqualTo(0x06);
        in.readUnsignedShort();
        in.readUnsignedShort();
    }

    private static void writeMultipleRegisters(DataOutputStream out, DataInputStream in, int address, int[] values)
            throws IOException {
        out.writeShort(2); // transaction id
        out.writeShort(0);
        out.writeShort(7 + values.length * 2);
        out.writeByte(1);
        out.writeByte(0x10);
        out.writeShort(address);
        out.writeShort(values.length);
        out.writeByte(values.length * 2);
        for (int v : values) {
            out.writeShort(v);
        }
        out.flush();

        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedByte();
        int functionCode = in.readUnsignedByte();
        assertThat(functionCode).isEqualTo(0x10);
        in.readUnsignedShort();
        in.readUnsignedShort();
    }
}
