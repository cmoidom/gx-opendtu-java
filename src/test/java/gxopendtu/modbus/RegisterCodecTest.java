package gxopendtu.modbus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterCodecTest {

    @Test
    void positiveValueUnchanged() {
        assertThat(RegisterCodec.toSigned16(500)).isEqualTo(500);
    }

    @Test
    void zeroUnchanged() {
        assertThat(RegisterCodec.toSigned16(0)).isEqualTo(0);
    }

    @Test
    void maxPositiveInt16Unchanged() {
        assertThat(RegisterCodec.toSigned16(32767)).isEqualTo(32767);
    }

    @Test
    void negativeValueConvertedFromTwosComplement() {
        // -500 W (exporting) is stored as 65036 in the unsigned register on the wire.
        assertThat(RegisterCodec.toSigned16(65036)).isEqualTo(-500);
    }

    @Test
    void boundaryJustAboveMaxPositiveIsNegative() {
        assertThat(RegisterCodec.toSigned16(32768)).isEqualTo(-32768);
    }

    @Test
    void combineBigEndianUint32PutsHighWordAtLowerAddress() {
        // 123.45 kWh at scale 100 -> raw 12345 = 0x3039 -> high=0x0000, low=0x3039
        assertThat(RegisterCodec.combineBigEndianUint32(0x0000, 0x3039)).isEqualTo(12345L);
        // A value spanning both words: 0x0001_86A0 = 100000
        assertThat(RegisterCodec.combineBigEndianUint32(0x0001, 0x86A0)).isEqualTo(100000L);
    }

    @Test
    void combineBigEndianUint32SwappedWordsWouldBeWrong() {
        // Demonstrates the pitfall this codec exists to avoid: swapping the
        // two registers produces a wildly different (wrong) value.
        long correct = RegisterCodec.combineBigEndianUint32(0x0001, 0x86A0);
        long swapped = RegisterCodec.combineBigEndianUint32(0x86A0, 0x0001);
        assertThat(correct).isNotEqualTo(swapped);
        assertThat(correct).isEqualTo(100000L);
    }
}
