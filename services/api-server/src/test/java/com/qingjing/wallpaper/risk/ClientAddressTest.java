package com.qingjing.wallpaper.risk;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
class ClientAddressTest {
    @Test void forwardedIpIsAcceptedOnlyFromConfiguredProxyAndNormalizesIpv6() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("198.51.100.1");request.addHeader("X-Real-IP","203.0.113.9");
        assertThat(new ClientAddress("127.0.0.1").of(request)).isEqualTo("198.51.100.1");
        request.setRemoteAddr("127.0.0.1");assertThat(new ClientAddress("127.0.0.1").of(request)).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.normalize("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
        assertThatThrownBy(()->ClientAddress.normalize("example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ClientAddress.normalize("1.2.3.999")).isInstanceOf(IllegalArgumentException.class);
    }
}
