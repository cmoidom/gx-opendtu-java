package gxopendtu.sunspec;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure SunSpec Modbus register-block builder/codec for the detection spike --
 * models 1 (Common), 101 (single-phase Inverter, matching this project's
 * single-phase-only install), 120 (Nameplate) and 123 (Immediate Controls).
 * No I/O: builds/reads a plain {@code int[]} of raw unsigned 16-bit register
 * values, kept separate from {@link SunSpecTcpServer}'s socket handling so
 * the encoding is unit-testable without a socket -- same split as
 * {@code modbus.RegisterCodec}/{@code modbus.ModbusTcpClient}.
 *
 * <p>Register layout (offsets from {@link #SUNSPEC_BASE}), confirmed against
 * a working Hoymiles-to-Victron bridge
 * (github.com/Geoffn-Hub/esphome-sunspec-proxy, SUNSPEC_BASE=40000,
 * OFF_M120=122, OFF_M123=150, OFF_END=176):
 * <pre>
 *   0-1     "SunS" marker
 *   2-69    Model 1 (Common), 68 registers (ID/L + 66)
 *   70-121  Model 101 (Inverter, single phase), 52 registers (ID/L + 50)
 *   122-149 Model 120 (Nameplate), 28 registers (ID/L + 26)
 *   150-175 Model 123 (Immediate Controls), 26 registers (ID/L + 24)
 *   176-177 End model (id=0xFFFF, length=0)
 * </pre>
 *
 * <p>Only {@code W} (Model 101) is wired to real telemetry
 * ({@link #setLivePowerW}) -- every other measurement point is a fixed,
 * clearly-placeholder value (single-phase 230V/50Hz assumption, 25 degC
 * cabinet temperature) or the formal SunSpec "not implemented" sentinel for
 * that type, since this spike only needs Venus OS to detect the device and
 * see a real production figure, not a fully-instrumented inverter. Model 123
 * (Immediate Controls) is genuinely read/write: Venus OS's writes to
 * {@code Conn}/{@code WMaxLimPct}/{@code WMaxLim_Ena} are stored here (so a
 * read-back sees them) but never forwarded anywhere -- {@link SunSpecTcpServer}
 * separately reports them to {@link SunSpecProxyState} for the /internal page.
 */
public final class SunSpecRegisterMap {

    public static final int SUNSPEC_BASE = 40000;
    public static final int TOTAL_REGISTERS = 178;

    private static final int NOT_IMPL_UINT16 = 0xFFFF;
    private static final int NOT_IMPL_INT16 = 0x8000;

    private static final int OFF_SUNS = 0;
    private static final int OFF_M1 = 2;
    private static final int OFF_M101 = 70;
    private static final int OFF_M120 = 122;
    private static final int OFF_M123 = 150;
    private static final int OFF_END = 176;

    // Model 101 sub-offsets actually written dynamically.
    private static final int M101_A = OFF_M101 + 2;
    private static final int M101_APHA = OFF_M101 + 3;
    private static final int M101_W = OFF_M101 + 14;
    private static final int M101_WH = OFF_M101 + 24; // acc32, big-endian (hi register first)
    private static final int M101_ST = OFF_M101 + 38;

    /** Matches PhVphA's fixed 230V placeholder -- derives A/AphA from real W so current isn't stuck at 0. */
    private static final double ASSUMED_VOLTAGE_V = 230.0;

    // Model 123 sub-offsets Venus OS is expected to read/write -- the only
    // writable points this spike handles (see SunSpecTcpServer).
    public static final int M123_CONN = OFF_M123 + 4;
    public static final int M123_WMAXLIMPCT = OFF_M123 + 5;
    public static final int M123_WMAXLIM_ENA = OFF_M123 + 9;

    /** WMaxLimPct is stored in tenths of a percent (SF=-1), e.g. 1000 = 100.0% -- same convention the reference bridge uses. */
    private static final int WMAXLIMPCT_SF_VALUE = -1;

    private final ReentrantLock lock = new ReentrantLock();
    private final int[] registers = new int[TOTAL_REGISTERS];

    public SunSpecRegisterMap(String manufacturer, String model, String serialNumber, double totalNominalPowerW) {
        writeSunS();
        writeModel1(manufacturer, model, serialNumber);
        writeModel101Static();
        writeModel120(totalNominalPowerW);
        writeModel123Static();
        writeEndModel();
    }

    private void writeSunS() {
        // "SunS" packed as two big-endian char-pair registers: 'S''u', 'n''S'.
        registers[OFF_SUNS] = 0x5375;
        registers[OFF_SUNS + 1] = 0x6e53;
    }

    private void writeModel1(String manufacturer, String model, String serialNumber) {
        int o = OFF_M1;
        registers[o] = 1; // ID
        registers[o + 1] = 66; // L
        packString(manufacturer, o + 2, 16);
        packString(model, o + 18, 16);
        packString("", o + 34, 8); // Opt
        packString("spike", o + 42, 8); // Vr
        packString(serialNumber, o + 50, 16);
        registers[o + 66] = 0; // DA -- RTU-only, unused over TCP
        registers[o + 67] = 0; // Pad
    }

    private void writeModel101Static() {
        int o = OFF_M101;
        registers[o] = 101; // ID
        registers[o + 1] = 50; // L
        registers[o + 6] = toUnsigned16(-1); // A_SF (0.1A resolution)
        registers[o + 10] = 230; // PhVphA -- fixed placeholder, single-phase 230V install
        registers[o + 13] = 0; // V_SF
        // W (offset 14) and W_SF (offset 15) set below, dynamic.
        registers[o + 15] = 0; // W_SF
        registers[o + 16] = 5000; // Hz -- fixed placeholder, 50.00 Hz
        registers[o + 17] = toUnsigned16(-2); // Hz_SF
        registers[o + 26] = 0; // WH_SF
        registers[o + 33] = 25; // TmpCab -- fixed placeholder, 25 degC
        registers[o + 37] = 0; // Tmp_SF
        // St (offset 38) set dynamically by setLivePowerW.
        registers[o + 39] = NOT_IMPL_UINT16; // StVnd

        // Not-implemented measurement points this spike doesn't wire up.
        setNotImplUint16(o + 4); // AphB
        setNotImplUint16(o + 5); // AphC
        setNotImplUint16(o + 7); // PPVphAB
        setNotImplUint16(o + 8); // PPVphBC
        setNotImplUint16(o + 9); // PPVphCA
        setNotImplUint16(o + 11); // PhVphB
        setNotImplUint16(o + 12); // PhVphC
        setNotImplInt16(o + 18); // VA
        setNotImplInt16(o + 20); // VAr
        setNotImplInt16(o + 22); // PF
        setNotImplUint16(o + 27); // DCA
        setNotImplUint16(o + 29); // DCV
        setNotImplInt16(o + 31); // DCW
        setNotImplInt16(o + 34); // TmpSnk
        setNotImplInt16(o + 35); // TmpTrns
        setNotImplInt16(o + 36); // TmpOt
        // Evt1/Evt2 (mandatory bitfield32): 0 = no active events, a genuine
        // (not sentinel) value -- left at the array's default 0.
        // EvtVnd1-4 (optional bitfield32): 0 is the SunSpec "not implemented"
        // convention for bitfield types too -- also left at default 0.
    }

    /** Called periodically with the real aggregate live power across every configured inverter. */
    public void setLivePowerW(double aggregateW) {
        // A_SF=-1 (0.1A resolution): P = V*I at near-unity PF, so I = P/V -- otherwise A/AphA
        // stay at their zero default forever even while W correctly shows real production.
        int currentRaw = toUnsigned16((int) Math.round(aggregateW / ASSUMED_VOLTAGE_V * 10));
        lock.lock();
        try {
            registers[M101_A] = currentRaw;
            registers[M101_APHA] = currentRaw;
            registers[M101_W] = toUnsigned16((int) Math.round(aggregateW));
            registers[M101_ST] = aggregateW > 0 ? 4 : 2; // 4=MPPT (producing), 2=SLEEPING
        } finally {
            lock.unlock();
        }
    }

    /**
     * Real aggregate lifetime AC yield (sum of every configured inverter's own OpenDTU-reported YieldTotal,
     * already in Wh) -- Model 101's WH accumulator, WH_SF fixed at 0 so the register holds whole Wh directly.
     * Big-endian word order (hi register first) matches this project's other 32-bit Modbus values -- see
     * {@code modbus.RegisterCodec#combineBigEndianUint32}.
     */
    public void setLifetimeEnergyWh(double wh) {
        long rounded = Math.max(0, Math.round(wh));
        int hi = (int) ((rounded >>> 16) & 0xFFFF);
        int lo = (int) (rounded & 0xFFFF);
        lock.lock();
        try {
            registers[M101_WH] = hi;
            registers[M101_WH + 1] = lo;
        } finally {
            lock.unlock();
        }
    }

    /** FC3 (Read Holding Registers): {@code count} values starting at {@code offset} (0-based, relative to SUNSPEC_BASE). */
    public int[] readRegisters(int offset, int count) {
        lock.lock();
        try {
            return Arrays.copyOfRange(registers, offset, offset + count);
        } finally {
            lock.unlock();
        }
    }

    /**
     * FC6/FC16 (Write Single/Multiple Registers): stores {@code values} starting at {@code offset}, so a
     * subsequent read reflects what was written -- normal SunSpec client behaviour is to read back after
     * writing to confirm. Never forwarded anywhere else; see SunSpecTcpServer for the /internal reporting hook.
     */
    public void writeRegisters(int offset, int[] values) {
        lock.lock();
        try {
            System.arraycopy(values, 0, registers, offset, values.length);
        } finally {
            lock.unlock();
        }
    }

    public boolean isValidRange(int offset, int count) {
        return offset >= 0 && count > 0 && offset + count <= TOTAL_REGISTERS;
    }

    private void writeModel120(double totalNominalPowerW) {
        int o = OFF_M120;
        int nominalRounded = (int) Math.round(totalNominalPowerW);
        registers[o] = 120; // ID
        registers[o + 1] = 26; // L
        registers[o + 2] = 4; // DERTyp = PV
        registers[o + 3] = nominalRounded; // WRtg
        registers[o + 4] = 0; // WRtg_SF
        registers[o + 5] = nominalRounded; // VARtg -- approximated as WRtg (near-unity PF)
        registers[o + 6] = 0; // VARtg_SF
        registers[o + 7] = 0; // VArRtgQ1
        registers[o + 8] = 0; // VArRtgQ2
        registers[o + 9] = 0; // VArRtgQ3
        registers[o + 10] = 0; // VArRtgQ4
        registers[o + 11] = 0; // VArRtg_SF
        registers[o + 12] = (int) Math.round(totalNominalPowerW / 230.0); // ARtg
        registers[o + 13] = 0; // ARtg_SF
        registers[o + 14] = 100; // PFRtgQ1 = 1.00 (SF=-2)
        registers[o + 15] = 100; // PFRtgQ2
        registers[o + 16] = 100; // PFRtgQ3
        registers[o + 17] = 100; // PFRtgQ4
        registers[o + 18] = toUnsigned16(-2); // PFRtg_SF
        setNotImplUint16(o + 19); // WHRtg (optional)
        registers[o + 20] = 0; // WHRtg_SF
        setNotImplUint16(o + 21); // AhrRtg (optional)
        registers[o + 22] = 0; // AhrRtg_SF
        setNotImplUint16(o + 23); // MaxChaRte (optional)
        registers[o + 24] = 0; // MaxChaRte_SF
        setNotImplUint16(o + 25); // MaxDisChaRte (optional)
        registers[o + 26] = 0; // MaxDisChaRte_SF
        registers[o + 27] = 0; // Pad
    }

    private void writeModel123Static() {
        int o = OFF_M123;
        registers[o] = 123; // ID
        registers[o + 1] = 24; // L
        registers[o + 2] = 0; // Conn_WinTms
        registers[o + 3] = 0; // Conn_RvrtTms
        registers[o + 4] = 1; // Conn = CONNECT
        registers[o + 5] = 1000; // WMaxLimPct = 100.0% (SF=-1), no limit by default
        registers[o + 6] = 0; // WMaxLimPct_WinTms
        registers[o + 7] = 0; // WMaxLimPct_RvrtTms
        registers[o + 8] = 0; // WMaxLimPct_RmpTms
        registers[o + 9] = 0; // WMaxLim_Ena = DISABLED by default
        registers[o + 10] = 1000; // OutPFSet = 1.00 (SF=-3), unity power factor
        registers[o + 11] = 0; // OutPFSet_WinTms
        registers[o + 12] = 0; // OutPFSet_RvrtTms
        registers[o + 13] = 0; // OutPFSet_RmpTms
        registers[o + 14] = 0; // OutPFSet_Ena = DISABLED, no reactive control on this install
        registers[o + 15] = 0; // VArWMaxPct
        registers[o + 16] = 0; // VArMaxPct
        registers[o + 17] = 0; // VArAvalPct
        registers[o + 18] = 0; // VArPct_WinTms
        registers[o + 19] = 0; // VArPct_RvrtTms
        registers[o + 20] = 0; // VArPct_RmpTms
        registers[o + 21] = 0; // VArPct_Mod = NONE
        registers[o + 22] = 0; // VArPct_Ena = DISABLED
        registers[o + 23] = toUnsigned16(WMAXLIMPCT_SF_VALUE); // WMaxLimPct_SF
        registers[o + 24] = toUnsigned16(-3); // OutPFSet_SF
        registers[o + 25] = toUnsigned16(-1); // VArPct_SF
    }

    private void writeEndModel() {
        registers[OFF_END] = 0xFFFF;
        registers[OFF_END + 1] = 0;
    }

    private void setNotImplUint16(int offset) {
        registers[offset] = NOT_IMPL_UINT16;
    }

    private void setNotImplInt16(int offset) {
        registers[offset] = NOT_IMPL_INT16;
    }

    private void packString(String value, int offset, int registerCount) {
        String s = value == null ? "" : value;
        for (int i = 0; i < registerCount; i++) {
            int charIndex = i * 2;
            int hi = charIndex < s.length() ? s.charAt(charIndex) : 0;
            int lo = charIndex + 1 < s.length() ? s.charAt(charIndex + 1) : 0;
            registers[offset + i] = ((hi & 0xFF) << 8) | (lo & 0xFF);
        }
    }

    private static int toUnsigned16(int signedValue) {
        return signedValue & 0xFFFF;
    }

    /** Real-decoded (SF-applied) WMaxLimPct percentage, from whatever is currently stored (defaults to 100.0). */
    public double wMaxLimPctPercent() {
        lock.lock();
        try {
            return registers[M123_WMAXLIMPCT] * Math.pow(10, WMAXLIMPCT_SF_VALUE);
        } finally {
            lock.unlock();
        }
    }

    public boolean wMaxLimEnabled() {
        lock.lock();
        try {
            return registers[M123_WMAXLIM_ENA] != 0;
        } finally {
            lock.unlock();
        }
    }

    public boolean connected() {
        lock.lock();
        try {
            return registers[M123_CONN] != 0;
        } finally {
            lock.unlock();
        }
    }
}
