package gxopendtu.modbus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Minimal Modbus TCP master supporting only function code 3 (Read Holding
 * Registers) -- the only operation this project needs.
 *
 * Written by hand instead of depending on a third-party Modbus library: the
 * Python reference project had to work around pymodbus renaming its unit-id
 * keyword across versions (unit=/slave=/device_id=) -- see
 * src/grid_meter_modbus.py's _read_holding_registers. Owning this ~100-line
 * client removes that whole class of dependency-version-churn risk.
 *
 * One TCP connection per instance, reconnected lazily on first use or after
 * a failure. Not thread-safe by design (each grid/battery reader owns its
 * own instance and is only ever called from the single control-loop thread).
 */
public final class ModbusTcpClient implements AutoCloseable {

    private static final int PROTOCOL_ID = 0x0000;
    private static final int FUNCTION_READ_HOLDING_REGISTERS = 0x03;

    private final String host;
    private final int port;
    private final Duration timeout;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int nextTransactionId;

    public ModbusTcpClient(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    private void connect() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            s.setSoTimeout((int) timeout.toMillis());
            socket = s;
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            throw new ModbusException("cannot connect to Modbus TCP at " + host + ":" + port, e);
        }
    }

    /** Reads {@code count} consecutive holding registers, returning raw unsigned 16-bit values. */
    public int[] readHoldingRegisters(int unitId, int address, int count) {
        connect();
        int transactionId = nextTransactionId = (nextTransactionId + 1) & 0xFFFF;
        try {
            writeRequest(transactionId, unitId, address, count);
            return readResponse(transactionId);
        } catch (IOException e) {
            close();
            throw new ModbusException("Modbus read failed at " + host + ":" + port, e);
        }
    }

    private void writeRequest(int transactionId, int unitId, int address, int count) throws IOException {
        out.writeShort(transactionId);
        out.writeShort(PROTOCOL_ID);
        out.writeShort(6); // following bytes: unitId(1) + functionCode(1) + address(2) + count(2)
        out.writeByte(unitId);
        out.writeByte(FUNCTION_READ_HOLDING_REGISTERS);
        out.writeShort(address);
        out.writeShort(count);
        out.flush();
    }

    private int[] readResponse(int expectedTransactionId) throws IOException {
        int respTransactionId = in.readUnsignedShort();
        in.readUnsignedShort(); // protocol id, always 0 -- not validated
        in.readUnsignedShort(); // length -- derivable from what follows, not validated
        in.readUnsignedByte(); // unit id echoed back -- not validated
        int functionCode = in.readUnsignedByte();

        if (respTransactionId != expectedTransactionId) {
            throw new ModbusException(
                    "unexpected Modbus transaction id: expected " + expectedTransactionId + ", got " + respTransactionId);
        }
        if ((functionCode & 0x80) != 0) {
            int exceptionCode = in.readUnsignedByte();
            throw new ModbusException(
                    "Modbus exception response: function=0x" + Integer.toHexString(functionCode & 0x7F)
                            + " exceptionCode=" + exceptionCode);
        }
        if (functionCode != FUNCTION_READ_HOLDING_REGISTERS) {
            throw new ModbusException("unexpected Modbus function code in response: " + functionCode);
        }

        int byteCount = in.readUnsignedByte();
        int[] registers = new int[byteCount / 2];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = in.readUnsignedShort();
        }
        return registers;
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        } finally {
            socket = null;
            in = null;
            out = null;
        }
    }
}
