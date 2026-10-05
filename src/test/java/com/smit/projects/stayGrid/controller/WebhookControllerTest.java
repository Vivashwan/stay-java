package com.smit.projects.stayGrid.controller;

import com.smit.projects.stayGrid.service.BookingService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests the Stripe webhook endpoint with real HMAC-SHA256 signatures,
 * built the same way Stripe signs requests: v1 = HMAC(secret, "timestamp.payload").
 */
@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    private static final String PAYLOAD = """
            {
              "id": "evt_test_1",
              "object": "event",
              "type": "checkout.session.completed",
              "data": { "object": { "id": "cs_test_123", "object": "checkout.session" } }
            }
            """;

    @Mock private BookingService bookingService;

    private WebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WebhookController(bookingService);
        ReflectionTestUtils.setField(controller, "endpointSecret", SECRET);
    }

    private static String signatureHeader(String payload, String secret, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    @Test
    @DisplayName("accepts a correctly signed event and passes it to the booking service")
    void acceptsValidSignature() throws Exception {
        ResponseEntity<Void> response =
                controller.capturePayments(PAYLOAD, signatureHeader(PAYLOAD, SECRET, now()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(bookingService).capturePayment(event.capture());
        assertThat(event.getValue().getId()).isEqualTo("evt_test_1");
        assertThat(event.getValue().getType()).isEqualTo("checkout.session.completed");
    }

    @Test
    @DisplayName("rejects a payload that was modified after signing")
    void rejectsTamperedPayload() throws Exception {
        String header = signatureHeader(PAYLOAD, SECRET, now());
        String tampered = PAYLOAD.replace("cs_test_123", "cs_attacker_999");

        assertThatThrownBy(() -> controller.capturePayments(tampered, header))
                .hasCauseInstanceOf(SignatureVerificationException.class);

        verify(bookingService, never()).capturePayment(any());
    }

    @Test
    @DisplayName("rejects an event signed with the wrong secret")
    void rejectsWrongSecret() throws Exception {
        String header = signatureHeader(PAYLOAD, "whsec_wrong_secret", now());

        assertThatThrownBy(() -> controller.capturePayments(PAYLOAD, header))
                .hasCauseInstanceOf(SignatureVerificationException.class);

        verify(bookingService, never()).capturePayment(any());
    }

    @Test
    @DisplayName("rejects a replayed event whose timestamp is outside the 5-minute tolerance")
    void rejectsStaleTimestamp() throws Exception {
        long tenMinutesAgo = now() - 600;
        String header = signatureHeader(PAYLOAD, SECRET, tenMinutesAgo);

        assertThatThrownBy(() -> controller.capturePayments(PAYLOAD, header))
                .hasCauseInstanceOf(SignatureVerificationException.class);

        verify(bookingService, never()).capturePayment(any());
    }

    @Test
    @DisplayName("rejects a malformed signature header")
    void rejectsMalformedHeader() {
        assertThatThrownBy(() -> controller.capturePayments(PAYLOAD, "not-a-stripe-signature"))
                .hasCauseInstanceOf(SignatureVerificationException.class);

        verify(bookingService, never()).capturePayment(any());
    }
}
