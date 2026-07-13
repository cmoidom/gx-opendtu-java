package gxopendtu.modbus;

/** Raised on any Modbus TCP connection, framing, or exception-response failure. */
public class ModbusException extends RuntimeException {

    private static final long serialVersionUID = 426067820272935987L;

    public ModbusException(String message) {
        super(message);
    }

    public ModbusException(String message, Throwable cause) {
        super(message, cause);
    }
}
