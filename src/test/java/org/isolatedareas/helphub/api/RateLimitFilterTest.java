package org.isolatedareas.helphub.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitFilterTest {
    @Test
    void countsEachWeChatUserOnTheirOwnEvenBehindOneGatewayAddress() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("10.0.0.9");
        first.addHeader("X-WX-OPENID", "resident-a");
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("10.0.0.9");
        second.addHeader("X-WX-OPENID", "resident-b");
        assertThat(RateLimitFilter.clientKey(first)).isEqualTo("wx:resident-a");
        assertThat(RateLimitFilter.clientKey(second)).isNotEqualTo(RateLimitFilter.clientKey(first));
    }

    @Test
    void countsOtherCallersByAddress() {
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("203.0.113.7");
        assertThat(RateLimitFilter.clientKey(direct)).isEqualTo("ip:203.0.113.7");
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.addHeader("X-Forwarded-For", "198.51.100.4, 10.0.0.1");
        assertThat(RateLimitFilter.clientKey(proxied)).isEqualTo("ip:198.51.100.4");
    }
}
