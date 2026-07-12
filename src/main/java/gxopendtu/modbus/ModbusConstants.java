package gxopendtu.modbus;

/** Constants shared between the grid and battery Modbus readers. */
public final class ModbusConstants {

    private ModbusConstants() {}

    /**
     * com.victronenergy.system aggregate service unit ID on the Cerbo GX --
     * always available regardless of which grid meter/battery model is
     * connected, so no per-install lookup is needed (unlike the grid meter's
     * own com.victronenergy.grid service, whose unit ID is its per-install
     * VRM device instance).
     */
    public static final int SYSTEM_UNIT_ID = 100;
}
