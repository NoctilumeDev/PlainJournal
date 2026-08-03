package com.ecommerce.payment.application.service;

import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class MockCallbackSignature {

    private MockCallbackSignature() {
    }

    public static boolean verify(CallbackCommand command, String secret) {
        byte[] expected = sign(command, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = command.signature().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    public static String sign(CallbackCommand command, String secret) {
        return signCanonical(canonical(command), secret);
    }

    public static boolean verify(RefundCallbackCommand command, String secret) {
        byte[] expected = sign(command, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = command.signature().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    public static String sign(RefundCallbackCommand command, String secret) {
        return signCanonical(canonical(command), secret);
    }

    private static String signCanonical(String canonical, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    static String canonical(CallbackCommand command) {
        return String.join("|",
                command.paymentNo(),
                command.externalEventId(),
                command.externalTransactionNo(),
                command.status(),
                command.amount().stripTrailingZeros().toPlainString(),
                Long.toString(command.timestamp()));
    }

    static String canonical(RefundCallbackCommand command) {
        return String.join("|",
                command.refundNo(),
                command.externalEventId(),
                command.externalRefundNo(),
                command.status(),
                command.amount().stripTrailingZeros().toPlainString(),
                Long.toString(command.timestamp()));
    }
}
