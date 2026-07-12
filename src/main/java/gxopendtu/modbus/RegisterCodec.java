package gxopendtu.modbus;

/**
 * Pure register-decoding helpers, kept separate from ModbusTcpClient's socket
 * I/O so they stay unit-testable without a socket.
 *
 * Port of the pure parts of src/grid_meter_modbus.py (_to_signed_int16 and the
 * inline 32-bit combination in _read_uint32_kwh).
 */
public final class RegisterCodec {

    private RegisterCodec() {}

    /** Registers arrive as unsigned 16-bit values over the wire; Victron's are signed. */
    public static int toSigned16(int raw) {
        return raw > 32767 ? raw - 65536 : raw;
    }

    /**
     * Victron's 32-bit Modbus-TCP registers are big-endian at the word level:
     * the high 16 bits live at the lower (first-read) register address.
     */
    public static long combineBigEndianUint32(int highRegister, int lowRegister) {
        return ((long) (highRegister & 0xFFFF) << 16) | (lowRegister & 0xFFFFL);
    }
}
