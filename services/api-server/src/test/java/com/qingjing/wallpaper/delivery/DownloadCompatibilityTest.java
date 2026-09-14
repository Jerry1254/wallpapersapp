package com.qingjing.wallpaper.delivery;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class DownloadCompatibilityTest {
    @Test void numericVersionsFailClosedForMissingMalformedOrOlderValues() {
        assertThat(DownloadTicketService.compatibleOs(null,null)).isTrue();
        assertThat(DownloadTicketService.compatibleOs("35","26")).isTrue();
        assertThat(DownloadTicketService.compatibleOs("10.2","10.2.0")).isTrue();
        assertThat(DownloadTicketService.compatibleOs("10.9","10.10")).isFalse();
        assertThat(DownloadTicketService.compatibleOs(null,"26")).isFalse();
        assertThat(DownloadTicketService.compatibleOs("99999999999999999999","26")).isFalse();
        assertThat(DownloadTicketService.compatibleOs("35","unrecognized")).isFalse();
    }
}
