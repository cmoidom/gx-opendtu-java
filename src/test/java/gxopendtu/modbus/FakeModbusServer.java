package gxopendtu.modbus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.IntFunction;

/**
 * Minimal single-connection Modbus TCP server stub, so client tests exercise
 * real MBAP framing/decoding over a real loopback socket instead of mocking
 * the transport away. Serves every request on the accepted connection until
 * the client closes it (needed for readers like ModbusGridMeter.readEnergyKwh
 * that issue two sequential reads over the same connection).
 *
 * Public: shared by gxopendtu.modbus, gxopendtu.grid and gxopendtu.battery tests.
 */
public final class FakeModbusServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private IntFunction<int[]> responseByAddress;
    private Integer exceptionCode;

    public FakeModbusServer() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Always answers with the same fixed registers, regardless of the requested address. */
    public void respondWithRegisters(int... registers) {
        respondPerAddress(address -> registers);
    }

    /** Answers each request by looking up its starting address via the given function. */
    public void respondPerAddress(IntFunction<int[]> responder) {
        this.responseByAddress = responder;
        this.exceptionCode = null;
        startServing();
    }

    public void respondWithException(int exceptionCode) {
        this.exceptionCode = exceptionCode;
        this.responseByAddress = null;
        startServing();
    }

    private void startServing() {
        Thread thread = new Thread(this::serveConnection, "fake-modbus-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void serveConnection() {
        try (Socket socket = serverSocket.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            while (true) {
                int transactionId;
                int address;
                try {
                    transactionId = in.readUnsignedShort();
                    in.readUnsignedShort(); // protocol id
                    in.readUnsignedShort(); // length
                    in.readUnsignedByte(); // unit id
                    in.readUnsignedByte(); // function code
                    address = in.readUnsignedShort();
                    in.readUnsignedShort(); // count
                } catch (EOFException e) {
                    break; // client closed the connection
                }

                out.writeShort(transactionId);
                out.writeShort(0);
                if (exceptionCode != null) {
                    out.writeShort(3); // unitId(1) + functionCode(1) + exceptionCode(1)
                    out.writeByte(1);
                    out.writeByte(0x83); // function code 3 | 0x80 (error bit)
                    out.writeByte(exceptionCode);
                } else {
                    int[] registers = responseByAddress.apply(address);
                    int byteCount = registers.length * 2;
                    out.writeShort(3 + byteCount); // unitId(1) + functionCode(1) + byteCount(1) + registers
                    out.writeByte(1);
                    out.writeByte(0x03);
                    out.writeByte(byteCount);
                    for (int reg : registers) {
                        out.writeShort(reg);
                    }
                }
                out.flush();
            }
        } catch (IOException ignored) {
            // expected on test teardown races (socket closed underneath the accept()/read())
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
