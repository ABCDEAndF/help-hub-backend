package org.isolatedareas.helphub.payments;

import com.wechat.pay.java.core.exception.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class PaymentController {
    private final PaymentService payments;
    private final WechatPayGateway gateway;

    public PaymentController(PaymentService payments, WechatPayGateway gateway) {
        this.payments = payments;
        this.gateway = gateway;
    }

    @PostMapping("/resident/payments")
    PaymentService.PaymentStart start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartPayment input) {
        return payments.start(CurrentUser.id(jwt), input.reservationId());
    }

    @GetMapping("/resident/payments")
    List<PaymentService.PaymentView> mine(@AuthenticationPrincipal Jwt jwt) {
        return payments.list(CurrentUser.id(jwt), false);
    }

    @PostMapping("/resident/payments/{id}/refund")
    PaymentService.RefundView residentRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                             @Valid @RequestBody RefundInput input) {
        return payments.requestRefund(CurrentUser.id(jwt), id, false, input.reason());
    }

    @GetMapping("/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    List<PaymentService.PaymentView> all(@AuthenticationPrincipal Jwt jwt) {
        return payments.list(CurrentUser.id(jwt), true);
    }

    @PostMapping("/admin/payments/{id}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    PaymentService.RefundView adminRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                          @Valid @RequestBody RefundInput input) {
        return payments.requestRefund(CurrentUser.id(jwt), id, true, input.reason());
    }

    @PostMapping("/payments/wechat/notify")
    void paymentNotification(@RequestBody String rawBody,
                             @RequestHeader("Wechatpay-Serial") String serial,
                             @RequestHeader("Wechatpay-Nonce") String nonce,
                             @RequestHeader("Wechatpay-Signature") String signature,
                             @RequestHeader("Wechatpay-Timestamp") String timestamp) {
        try {
            payments.applyPayment(gateway.parsePayment(
                new WechatPayGateway.CallbackHeaders(serial, nonce, signature, timestamp), rawBody));
        } catch (ValidationException invalidSignature) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid WeChat Pay signature");
        }
    }

    @PostMapping("/payments/wechat/refund-notify")
    void refundNotification(@RequestBody String rawBody,
                            @RequestHeader("Wechatpay-Serial") String serial,
                            @RequestHeader("Wechatpay-Nonce") String nonce,
                            @RequestHeader("Wechatpay-Signature") String signature,
                            @RequestHeader("Wechatpay-Timestamp") String timestamp) {
        try {
            payments.applyRefund(gateway.parseRefund(
                new WechatPayGateway.CallbackHeaders(serial, nonce, signature, timestamp), rawBody));
        } catch (ValidationException invalidSignature) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid WeChat Pay signature");
        }
    }

    public record StartPayment(long reservationId) {}
    public record RefundInput(@Size(max = 255) String reason) {}
}
