package gxopendtu.modbus;

/** Raised on any Modbus TCP connection, framing, or exception-response failure. */
public class ModbusException extends RuntimeException {

    public ModbusException(String message) {
        super(message);
    }

    public ModbusException(String message, Throwable cause) {
        super(message, cause);
    }
}
