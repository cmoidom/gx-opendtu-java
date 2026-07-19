package gxopendtu.sunspec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SunSpecRegisterMapTest {

    private final SunSpecRegisterMap map = new SunSpecRegisterMap("Fronius", "gx-opendtu-java", "SN123", 3980.0);

    @Test
    void marksSunSHeaderAtOffsetZero() {
        int[] regs = map.readRegisters(0, 2);
        assertThat(regs).containsExactly(0x5375, 0x6e53);
    }

    @Test
    void chainsModelIdsAndLengthsAtExpectedOffsets() {
        assertThat(map.readRegisters(2, 2)).containsExactly(1, 66); // Model 1 (Common)
        assertThat(map.readRegisters(70, 2)).containsExactly(101, 50); // Model 101 (Inverter)
        assertThat(map.readRegisters(122, 2)).containsExactly(120, 26); // Model 120 (Nameplate)
        assertThat(map.readRegisters(150, 2)).containsExactly(123, 24); // Model 123 (Immediate Controls)
        assertThat(map.readRegisters(176, 2)).containsExactly(0xFFFF, 0); // End model
    }

    @Test
    void totalBlockIsExactly178Registers() {
        assertThat(SunSpecRegisterMap.TOTAL_REGISTERS).isEqualTo(178);
        assertThat(map.readRegisters(0, 178)).hasSize(178);
    }

    @Test
    void packsManufacturerAndModelStringsBigEndianCharPairs() {
        int[] mn = map.readRegisters(4, 16); // Model 1 offset 2 + Mn at +2
        assertThat(mn[0]).isEqualTo(('F' << 8) | 'r');
        assertThat(mn[1]).isEqualTo(('o' << 8) | 'n');
        assertThat(mn[2]).isEqualTo(('i' << 8) | 'u');
        assertThat(mn[3]).isEqualTo(('s' << 8) | 0);
        assertThat(mn[4]).isEqualTo(0);
    }

    @Test
    void nameplateReportsRealTotalNominalPower() {
        int[] m120 = map.readRegisters(122, 28);
        assertThat(m120[2]).isEqualTo(4); // DERTyp = PV
        assertThat(m120[3]).isEqualTo(3980); // WRtg
        assertThat(m120[4]).isEqualTo(0); // WRtg_SF
    }

    @Test
    void defaultsToNoLimitAndDisabledOnControlBlock() {
        assertThat(map.wMaxLimPctPercent()).isEqualTo(100.0);
        assertThat(map.wMaxLimEnabled()).isFalse();
        assertThat(map.connected()).isTrue();
    }

    @Test
    void setLivePowerWUpdatesModel101WRegisterAndOperatingState() {
        map.setLivePowerW(1234.0);
        int[] m101 = map.readRegisters(70, 52);
        assertThat(m101[14]).isEqualTo(1234); // W
        assertThat(m101[38]).isEqualTo(4); // St = MPPT (producing)

        map.setLivePowerW(0.0);
        assertThat(map.readRegisters(70, 52)[38]).isEqualTo(2); // St = SLEEPING
    }

    @Test
    void setAcMeasurementsWritesRealCurrentAndVoltage() {
        // 8.4A (A_SF=-1 -> raw 84) and 237.1V (V_SF=-1 -> raw 2371).
        map.setAcMeasurements(8.4, 237.1);
        int[] m101 = map.readRegisters(70, 52);
        assertThat(m101[2]).isEqualTo(84); // A
        assertThat(m101[3]).isEqualTo(84); // AphA
        assertThat(m101[10]).isEqualTo(2371); // PhVphA
    }

    @Test
    void setLifetimeEnergyWhSplitsIntoBigEndianAcc32() {
        map.setLifetimeEnergyWh(1885189.0);
        int[] m101 = map.readRegisters(70, 52);
        long rebuilt = ((long) m101[24] << 16) | (m101[25] & 0xFFFFL);
        assertThat(rebuilt).isEqualTo(1885189L);
    }

    @Test
    void setLifetimeEnergyWhClampsNegativeToZero() {
        map.setLifetimeEnergyWh(-5.0);
        int[] m101 = map.readRegisters(70, 52);
        assertThat(m101[24]).isEqualTo(0);
        assertThat(m101[25]).isEqualTo(0);
    }

    @Test
    void writeRegistersToControlBlockIsReflectedOnReadBack() {
        // WMaxLimPct = 55.0% (tenths of percent, SF=-1) and WMaxLim_Ena = 1 (ENABLED).
        map.writeRegisters(SunSpecRegisterMap.M123_WMAXLIMPCT, new int[] {550});
        map.writeRegisters(SunSpecRegisterMap.M123_WMAXLIM_ENA, new int[] {1});

        assertThat(map.wMaxLimPctPercent()).isEqualTo(55.0);
        assertThat(map.wMaxLimEnabled()).isTrue();
    }

    @Test
    void writeRegistersNeverTouchesUnrelatedOffsets() {
        int[] before = map.readRegisters(70, 52); // Model 101, untouched by a Model 123 write
        map.writeRegisters(SunSpecRegisterMap.M123_CONN, new int[] {0});
        assertThat(map.readRegisters(70, 52)).isEqualTo(before);
    }

    @Test
    void isValidRangeRejectsOutOfBoundsRequests() {
        assertThat(map.isValidRange(0, 178)).isTrue();
        assertThat(map.isValidRange(0, 179)).isFalse();
        assertThat(map.isValidRange(177, 2)).isFalse();
        assertThat(map.isValidRange(-1, 1)).isFalse();
        assertThat(map.isValidRange(0, 0)).isFalse();
    }
}
