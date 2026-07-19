package gxopendtu.sunspec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal Modbus TCP slave (server) exposing {@link SunSpecRegisterMap} --
 * the mirror image of {@code modbus.ModbusTcpClient} (which only ever plays
 * the master/client role talking to the grid meter/battery). Supports only
 * what a SunSpec master needs: FC3 (Read Holding Registers), FC6 (Write
 * Single Register) and FC16 (Write Multiple Registers).
 *
 * Writes to the Model 123 control block (Conn, WMaxLimPct, WMaxLim_Ena) are
 * stored (so a read-back reflects them, normal SunSpec client behaviour) and
 * reported to {@link SunSpecProxyState} for the /internal page; this class
 * itself never acts on them -- see {@code SunSpecForwarder} for the separate,
 * opt-in path that forwards them to real OpenDTU commands.
 *
 * One daemon thread per accepted connection -- a single client (Venus OS) is
 * the only expected peer, so no connection pool/executor tuning is needed.
 */
public final class SunSpecTcpServer {

    private static final Logger LOG = Logger.getLogger(SunSpecTcpServer.class.getName());

    private static final int FUNCTION_READ_HOLDING_REGISTERS = 0x03;
    private static final int FUNCTION_WRITE_SINGLE_REGISTER = 0x06;
    private static final int FUNCTION_WRITE_MULTIPLE_REGISTERS = 0x10;
    private static final int EXCEPTION_ILLEGAL_FUNCTION = 0x01;
    private static final int EXCEPTION_ILLEGAL_DATA_ADDRESS = 0x02;

    private final int port;
    private final SunSpecRegisterMap registerMap;
    private final SunSpecProxyState state;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public SunSpecTcpServer(int port, SunSpecRegisterMap registerMap, SunSpecProxyState state) {
        this.port = port;
        this.registerMap = registerMap;
        this.state = state;
    }

    /** Binds and starts accepting connections on a daemon thread; returns immediately. */
    public void start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            throw new SunSpecProxyException("cannot bind SunSpec proxy TCP server on port " + port, e);
        }
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "sunspec-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOG.info("[SunSpec] serveur Modbus TCP demarre sur le port " + serverSocket.getLocalPort());
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        }
    }

    /** Actual bound port -- differs from the configured port when 0 (any free port) was requested, e.g. in tests. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                state.recordConnectionOpened();
                Thread clientThread = new Thread(() -> handleClient(socket), "sunspec-proxy-client");
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "[SunSpec] erreur d'acceptation de connexion", e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String remoteAddress = socket.getRemoteSocketAddress().toString();
        LOG.info("[SunSpec] connexion TCP acceptee depuis " + remoteAddress);
        try (Socket s = socket;
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
            while (running) {
                handleOneRequest(in, out, remoteAddress);
            }
        } catch (EOFException e) {
            LOG.info("[SunSpec] connexion fermee par le client: " + remoteAddress);
        } catch (IOException e) {
            LOG.log(Level.INFO, "[SunSpec] connexion fermee (erreur): " + remoteAddress + " -- " + e.getMessage());
        }
    }

    private void handleOneRequest(DataInputStream in, DataOutputStream out, String remoteAddress) throws IOException {
        int transactionId = in.readUnsignedShort();
        in.readUnsignedShort(); // protocol id, always 0
        in.readUnsignedShort(); // length -- derivable from what follows, not validated
        int unitId = in.readUnsignedByte();
        int functionCode = in.readUnsignedByte();

        switch (functionCode) {
            case FUNCTION_READ_HOLDING_REGISTERS -> handleReadHoldingRegisters(in, out, transactionId, unitId);
            case FUNCTION_WRITE_SINGLE_REGISTER ->
                    handleWriteSingleRegister(in, out, transactionId, unitId, remoteAddress);
            case FUNCTION_WRITE_MULTIPLE_REGISTERS ->
                    handleWriteMultipleRegisters(in, out, transactionId, unitId, remoteAddress);
            default -> {
                LOG.warning("[SunSpec] fonction Modbus non supportee: 0x" + Integer.toHexString(functionCode));
                sendException(out, transactionId, unitId, functionCode, EXCEPTION_ILLEGAL_FUNCTION);
            }
        }
    }

    private void handleReadHoldingRegisters(DataInputStream in, DataOutputStream out, int transactionId, int unitId)
            throws IOException {
        int address = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        int offset = address - SunSpecRegisterMap.SUNSPEC_BASE;

        if (!registerMap.isValidRange(offset, count)) {
            LOG.warning("[SunSpec] lecture FC3 hors plage: adresse=" + address + " nb=" + count);
            sendException(out, transactionId, unitId, FUNCTION_READ_HOLDING_REGISTERS, EXCEPTION_ILLEGAL_DATA_ADDRESS);
            return;
        }

        int[] values = registerMap.readRegisters(offset, count);
        out.writeShort(transactionId);
        out.writeShort(0); // protocol id
        out.writeShort(3 + values.length * 2); // unitId + functionCode + byteCount + registers
        out.writeByte(unitId);
        out.writeByte(FUNCTION_READ_HOLDING_REGISTERS);
        out.writeByte(values.length * 2);
        for (int v : values) {
            out.writeShort(v);
        }
        out.flush();
    }

    private void handleWriteSingleRegister(
            DataInputStream in, DataOutputStream out, int transactionId, int unitId, String remoteAddress)
            throws IOException {
        int address = in.readUnsignedShort();
        int value = in.readUnsignedShort();
        int offset = address - SunSpecRegisterMap.SUNSPEC_BASE;

        if (!registerMap.isValidRange(offset, 1)) {
            LOG.warning("[SunSpec] ecriture FC6 hors plage: adresse=" + address);
            sendException(out, transactionId, unitId, FUNCTION_WRITE_SINGLE_REGISTER, EXCEPTION_ILLEGAL_DATA_ADDRESS);
            return;
        }
        registerMap.writeRegisters(offset, new int[] {value});
        recordIfControlWrite(offset, remoteAddress);

        out.writeShort(transactionId);
        out.writeShort(0);
        out.writeShort(6);
        out.writeByte(unitId);
        out.writeByte(FUNCTION_WRITE_SINGLE_REGISTER);
        out.writeShort(address);
        out.writeShort(value);
        out.flush();
    }

    private void handleWriteMultipleRegisters(
            DataInputStream in, DataOutputStream out, int transactionId, int unitId, String remoteAddress)
            throws IOException {
        int address = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        int byteCount = in.readUnsignedByte();
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readUnsignedShort();
        }
        // byteCount is redundant with count for a well-formed request -- not independently validated.
        int offset = address - SunSpecRegisterMap.SUNSPEC_BASE;

        if (!registerMap.isValidRange(offset, count) || byteCount != count * 2) {
            LOG.warning("[SunSpec] ecriture FC16 hors plage: adresse=" + address + " nb=" + count);
            sendException(
                    out, transactionId, unitId, FUNCTION_WRITE_MULTIPLE_REGISTERS, EXCEPTION_ILLEGAL_DATA_ADDRESS);
            return;
        }
        registerMap.writeRegisters(offset, values);
        recordIfControlWrite(offset, remoteAddress);

        out.writeShort(transactionId);
        out.writeShort(0);
        out.writeShort(6);
        out.writeByte(unitId);
        out.writeByte(FUNCTION_WRITE_MULTIPLE_REGISTERS);
        out.writeShort(address);
        out.writeShort(count);
        out.flush();
    }

    private void recordIfControlWrite(int offset, String remoteAddress) {
        if (offset == SunSpecRegisterMap.M123_CONN
                || offset == SunSpecRegisterMap.M123_WMAXLIMPCT
                || offset == SunSpecRegisterMap.M123_WMAXLIM_ENA) {
            state.recordControlWrite(
                    registerMap.wMaxLimPctPercent(),
                    registerMap.wMaxLimEnabled(),
                    registerMap.connected(),
                    remoteAddress,
                    System.currentTimeMillis() / 1000.0);
        }
    }

    private void sendException(DataOutputStream out, int transactionId, int unitId, int functionCode, int exceptionCode)
            throws IOException {
        out.writeShort(transactionId);
        out.writeShort(0);
        out.writeShort(3);
        out.writeByte(unitId);
        out.writeByte(functionCode | 0x80);
        out.writeByte(exceptionCode);
        out.flush();
    }
}
