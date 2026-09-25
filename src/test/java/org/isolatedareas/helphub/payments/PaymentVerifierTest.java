package org.isolatedareas.helphub.payments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentVerifierTest {
    private final PaymentVerifier verifier = new PaymentVerifier();
    private final PaymentVerifier.ExpectedPayment expected =
        new PaymentVerifier.ExpectedPayment(1200, "CNY", "openid-1", "wx-app", "merchant-1");

    @Test
    void acceptsAnExactSuccessfulPayment() {
        verifier.verify(expected, notice("SUCCESS", 1200, "CNY", "openid-1", "wx-app", "merchant-1"));
    }

    @Test
    void rejectsEverySettlementIdentityMismatch() {
        assertRejected(notice("NOTPAY", 1200, "CNY", "openid-1", "wx-app", "merchant-1"));
        assertRejected(notice("SUCCESS", 1199, "CNY", "openid-1", "wx-app", "merchant-1"));
        assertRejected(notice("SUCCESS", 1200, "USD", "openid-1", "wx-app", "merchant-1"));
        assertRejected(notice("SUCCESS", 1200, "CNY", "other-user", "wx-app", "merchant-1"));
        assertRejected(notice("SUCCESS", 1200, "CNY", "openid-1", "other-app", "merchant-1"));
        assertRejected(notice("SUCCESS", 1200, "CNY", "openid-1", "wx-app", "other-merchant"));
    }

    private void assertRejected(WechatPayGateway.PaymentNotice notice) {
        assertThatThrownBy(() -> verifier.verify(expected, notice))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409 CONFLICT");
    }

    private WechatPayGateway.PaymentNotice notice(String state, Integer amount, String currency,
                                                   String openId, String appId, String merchantId) {
        return new WechatPayGateway.PaymentNotice("order-1", "transaction-1", state, amount,
            currency, openId, appId, merchantId, "2026-09-06T10:00:00+08:00");
    }
}
