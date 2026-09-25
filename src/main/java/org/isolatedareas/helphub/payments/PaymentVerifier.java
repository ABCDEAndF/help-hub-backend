package org.isolatedareas.helphub.payments;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Verifies that a signed WeChat notification belongs to the exact local order being settled. */
@Component
public class PaymentVerifier {
    public void verify(ExpectedPayment expected, WechatPayGateway.PaymentNotice notice) {
        boolean matches = notice != null
            && "SUCCESS".equals(notice.tradeState())
            && notice.amountFen() != null
            && notice.amountFen() == expected.amountFen()
            && Objects.equals(expected.currency(), notice.currency())
            && Objects.equals(expected.openId(), notice.payerOpenId())
            && Objects.equals(expected.appId(), notice.appId())
            && Objects.equals(expected.merchantId(), notice.merchantId());
        if (!matches) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Payment notification does not match the order");
        }
    }

    public record ExpectedPayment(int amountFen, String currency, String openId,
                                  String appId, String merchantId) {}
}
