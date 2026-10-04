package com.qingjing.wallpaper.appupdate;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ApkByteRangeTest {
    @Test void fullAndResumeAndSuffixRangesHaveCorrectBounds() {
        assertThat(ApkByteRange.parse(null,100)).isEqualTo(new ApkByteRange(0,99,false));
        assertThat(ApkByteRange.parse("bytes=50-",100)).isEqualTo(new ApkByteRange(50,99,true));
        assertThat(ApkByteRange.parse("bytes=20-30",100).length()).isEqualTo(11);
        assertThat(ApkByteRange.parse("bytes=-10",100)).isEqualTo(new ApkByteRange(90,99,true));
        assertThat(ApkByteRange.parse("bytes=20-1000",100)).isEqualTo(new ApkByteRange(20,99,true));
    }
    @Test void malformedOverlappingAndOutOfBoundsRangesAreRejected() {
        for (String value : new String[]{"bytes=100-", "bytes=9-1", "bytes=-0", "bytes=0-2,4-6", "items=1-2", "bytes=9999999999999999999999999-"}) {
            assertThatThrownBy(() -> ApkByteRange.parse(value,100)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
