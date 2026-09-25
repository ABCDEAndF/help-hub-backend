package org.isolatedareas.helphub.payments;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses the official WeChat Pay API v3 SDK for request signing, response verification and callback decryption. */
@Component
public class WechatPayGateway {
    private final String appId;
    private final String merchantId;
    private final String merchantSerial;
    private final String privateKeyPath;
    private final String apiV3Key;
    private final String paymentNotifyUrl;
    private final String refundNotifyUrl;
    private volatile Services services;

    public WechatPayGateway(
        @Value("${app.wechat.app-id}") String appId,
        @Value("${app.wechat-pay.merchant-id:}") String merchantId,
        @Value("${app.wechat-pay.merchant-serial:}") String merchantSerial,
        @Value("${app.wechat-pay.private-key-path:}") String privateKeyPath,
        @Value("${app.wechat-pay.api-v3-key:}") String apiV3Key,
        @Value("${app.wechat-pay.payment-notify-url:}") String paymentNotifyUrl,
        @Value("${app.wechat-pay.refund-notify-url:}") String refundNotifyUrl
    ) {
        this.appId = appId;
        this.merchantId = merchantId;
        this.merchantSerial = merchantSerial;
        this.privateKeyPath = privateKeyPath;
        this.apiV3Key = apiV3Key;
        this.paymentNotifyUrl = paymentNotifyUrl;
        this.refundNotifyUrl = refundNotifyUrl;
    }

    public boolean configured() {
        return present(appId) && present(merchantId) && present(merchantSerial) && present(privateKeyPath)
            && present(apiV3Key) && present(paymentNotifyUrl) && present(refundNotifyUrl);
    }

    public PrepayData prepay(String outTradeNo, String openId, String description, int amountFen, Instant expiresAt) {
        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId);
        request.setMchid(merchantId);
        request.setDescription(description.length() > 127 ? description.substring(0, 127) : description);
        request.setOutTradeNo(outTradeNo);
        request.setTimeExpire(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(expiresAt.atOffset(ZoneOffset.ofHours(8))));
        request.setNotifyUrl(paymentNotifyUrl);
        Amount amount = new Amount();
        amount.setTotal(amountFen);
        amount.setCurrency("CNY");
        request.setAmount(amount);
        Payer payer = new Payer();
        payer.setOpenid(openId);
        request.setPayer(payer);
        PrepayWithRequestPaymentResponse response = services().jsapi().prepayWithRequestPayment(request);
        return new PrepayData(response.getAppId(), response.getTimeStamp(), response.getNonceStr(),
            response.getPackageVal(), response.getSignType(), response.getPaySign());
    }

    public void close(String outTradeNo) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setMchid(merchantId);
        request.setOutTradeNo(outTradeNo);
        services().jsapi().closeOrder(request);
    }

    public PaymentNotice parsePayment(CallbackHeaders headers, String rawBody) {
        Transaction transaction = services().parser().parse(requestParam(headers, rawBody), Transaction.class);
        return paymentNotice(transaction);
    }

    public PaymentNotice query(String outTradeNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(merchantId);
        request.setOutTradeNo(outTradeNo);
        return paymentNotice(services().jsapi().queryOrderByOutTradeNo(request));
    }

    private PaymentNotice paymentNotice(Transaction transaction) {
        return new PaymentNotice(transaction.getOutTradeNo(), transaction.getTransactionId(),
            transaction.getTradeState() == null ? null : transaction.getTradeState().name(),
            transaction.getAmount() == null ? null : transaction.getAmount().getTotal(),
            transaction.getAmount() == null ? null : transaction.getAmount().getCurrency(),
            transaction.getPayer() == null ? null : transaction.getPayer().getOpenid(),
            transaction.getAppid(), transaction.getMchid(), transaction.getSuccessTime());
    }

    public RefundData refund(String outTradeNo, String outRefundNo, int amountFen, String reason) {
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(outTradeNo);
        request.setOutRefundNo(outRefundNo);
        request.setReason(reason);
        request.setNotifyUrl(refundNotifyUrl);
        AmountReq amount = new AmountReq();
        amount.setRefund((long) amountFen);
        amount.setTotal((long) amountFen);
        amount.setCurrency("CNY");
        request.setAmount(amount);
        return refundData(services().refund().create(request));
    }

    public RefundData queryRefund(String outRefundNo) {
        QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
        request.setOutRefundNo(outRefundNo);
        return refundData(services().refund().queryByOutRefundNo(request));
    }

    private RefundData refundData(Refund response) {
        return new RefundData(response.getOutTradeNo(), response.getOutRefundNo(), response.getRefundId(),
            response.getStatus() == null ? "PROCESSING" : response.getStatus().name(),
            response.getAmount() == null ? null : response.getAmount().getRefund(),
            response.getAmount() == null ? null : response.getAmount().getTotal(),
            response.getAmount() == null ? null : response.getAmount().getCurrency());
    }

    public RefundNotice parseRefund(CallbackHeaders headers, String rawBody) {
        RefundNotification refund = services().parser().parse(requestParam(headers, rawBody), RefundNotification.class);
        return new RefundNotice(refund.getOutTradeNo(), refund.getOutRefundNo(), refund.getRefundId(),
            refund.getRefundStatus() == null ? null : refund.getRefundStatus().name(),
            refund.getAmount() == null ? null : refund.getAmount().getRefund(), refund.getSuccessTime());
    }

    public String appId() { return appId; }
    public String merchantId() { return merchantId; }

    private Services services() {
        if (!configured()) throw new IllegalStateException("WeChat Pay merchant credentials are not configured");
        Services current = services;
        if (current != null) return current;
        synchronized (this) {
            if (services == null) {
                Config config = new RSAAutoCertificateConfig.Builder()
                    .merchantId(merchantId)
                    .privateKeyFromPath(privateKeyPath)
                    .merchantSerialNumber(merchantSerial)
                    .apiV3Key(apiV3Key)
                    .build();
                services = new Services(new JsapiServiceExtension.Builder().config(config).build(),
                    new RefundService.Builder().config(config).build(),
                    new NotificationParser((NotificationConfig) config));
            }
            return services;
        }
    }

    private RequestParam requestParam(CallbackHeaders headers, String rawBody) {
        return new RequestParam.Builder().serialNumber(headers.serial()).nonce(headers.nonce())
            .signature(headers.signature()).timestamp(headers.timestamp()).body(rawBody).build();
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }

    private record Services(JsapiServiceExtension jsapi, RefundService refund, NotificationParser parser) {}
    public record CallbackHeaders(String serial, String nonce, String signature, String timestamp) {}
    public record PrepayData(String appId, String timeStamp, String nonceStr, String packageValue,
                             String signType, String paySign) {}
    public record PaymentNotice(String outTradeNo, String transactionId, String tradeState, Integer amountFen,
                                String currency, String payerOpenId, String appId, String merchantId,
                                String successTime) {}
    public record RefundData(String outTradeNo, String outRefundNo, String refundId, String status,
                             Long amountFen, Long totalFen, String currency) {}
    public record RefundNotice(String outTradeNo, String outRefundNo, String refundId, String status,
                               Long amountFen, String successTime) {}
}
