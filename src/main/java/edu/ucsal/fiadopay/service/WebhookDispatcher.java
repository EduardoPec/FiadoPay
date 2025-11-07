package edu.ucsal.fiadopay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ucsal.fiadopay.domain.Merchant;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.domain.WebhookDelivery;
import edu.ucsal.fiadopay.repo.MerchantRepository;
import edu.ucsal.fiadopay.repo.PaymentRepository;
import edu.ucsal.fiadopay.repo.WebhookDeliveryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;

@Service
public class WebhookDispatcher {

    private final ThreadPoolTaskExecutor webhookExecutor;
    private final TaskScheduler taskScheduler;
    private final WebhookDeliveryRepository deliveries;
    private final PaymentRepository payments;
    private final MerchantRepository merchants;
    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${fiadopay.webhook-secret:ucsal-2025}")
    private String webhookSecret;

    public WebhookDispatcher(@Qualifier("webhookExecutor") ThreadPoolTaskExecutor webhookExecutor,
                             TaskScheduler taskScheduler,
                             WebhookDeliveryRepository deliveries,
                             PaymentRepository payments,
                             MerchantRepository merchants,
                             ObjectMapper mapper) {
        this.webhookExecutor = webhookExecutor;
        this.taskScheduler = taskScheduler;
        this.deliveries = deliveries;
        this.payments = payments;
        this.merchants = merchants;
        this.mapper = mapper;
    }

    public void enqueueDelivery(String paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow();
        Merchant m = merchants.findById(p.getMerchantId()).orElseThrow();

        WebhookDelivery d = WebhookDelivery.builder()
                .eventId("evt_" + paymentId)
                .eventType("payment.updated")
                .paymentId(p.getId())
                .targetUrl(m.getWebhookUrl())
                .signature("")
                .payload("")
                .attempts(0)
                .delivered(false)
                .lastAttemptAt(null)
                .build();

        d = deliveries.save(d);
        scheduleTryDeliver(d.getId(), 0);
    }

    public void scheduleTryDeliver(Long deliveryId, int attempt) {
        long delaySec = Math.min(30, (long) Math.pow(2, Math.max(0, attempt)));
        taskScheduler.schedule(
                () -> {
                    try {
                        tryDeliver(deliveryId);
                    } catch (Exception e) {
                        scheduleTryDeliver(deliveryId, attempt + 1);
                    }
                },
                Date.from(Instant.now().plusSeconds(delaySec))
        );
    }

    public void tryDeliver(Long deliveryId) throws Exception {
        WebhookDelivery d = deliveries.findById(deliveryId).orElseThrow();
        if (d.isDelivered()) return;

        Payment p = payments.findById(d.getPaymentId()).orElseThrow();
        Merchant m = merchants.findById(p.getMerchantId()).orElseThrow();

        Map<String, Object> payload = Map.of(
                "paymentId", p.getId(),
                "status", p.getStatus().name(),
                "amount", p.getAmount(),
                "merchantId", p.getMerchantId(),
                "occurredAt", Instant.now().toString()
        );
        byte[] body = mapper.writeValueAsBytes(payload);
        String signature = hmacSha256Hex(webhookSecret, body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(m.getWebhookUrl()))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-Event-Type", "payment.updated")
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        d.setAttempts(d.getAttempts() + 1);
        d.setLastAttemptAt(Instant.now());
        d.setSignature(signature);
        d.setPayload(new String(body, StandardCharsets.UTF_8));

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            d.setDelivered(true);
        } else {
            deliveries.save(d);
            throw new IllegalStateException("Webhook failed with status " + resp.statusCode());
        }
        deliveries.save(d);
    }

    private String hmacSha256Hex(String secret, byte[] message) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        var keySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] sig = mac.doFinal(message);
        return HexFormat.of().formatHex(sig);
    }

    @Scheduled(initialDelay = 10_000, fixedRate = 60_000)
    public void startRedeliveryJob() {
        deliveries.findAll().stream()
                .filter(d -> !d.isDelivered())
                .forEach(d -> scheduleTryDeliver(d.getId(), Math.max(0, d.getAttempts())));
    }
}
