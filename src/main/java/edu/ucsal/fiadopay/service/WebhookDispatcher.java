package edu.ucsal.fiadopay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ucsal.fiadopay.concurrent.WorkerPool;
import edu.ucsal.fiadopay.domain.Merchant;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.domain.WebhookDelivery;
import edu.ucsal.fiadopay.repo.MerchantRepository;
import edu.ucsal.fiadopay.repo.PaymentRepository;
import edu.ucsal.fiadopay.repo.WebhookDeliveryRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class WebhookDispatcher {

    private final WorkerPool workerPool;
    private final WebhookDeliveryRepository deliveries;
    private final PaymentRepository payments;
    private final MerchantRepository merchants;
    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${fiadopay.webhook-secret:ucsal-2025}")
    private String webhookSecret;

    public WebhookDispatcher(WorkerPool workerPool,
                             WebhookDeliveryRepository deliveries,
                             PaymentRepository payments,
                             MerchantRepository merchants,
                             ObjectMapper mapper) {
        this.workerPool = workerPool;
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
        workerPool.scheduler().schedule(() -> {
            try {
                tryDeliver(deliveryId);
            } catch (Exception e) {
                scheduleTryDeliver(deliveryId, attempt + 1);
            }
        }, delaySec, TimeUnit.SECONDS);
    }

    public void tryDeliver(Long deliveryId) throws Exception {
        WebhookDelivery d = deliveries.findById(deliveryId).orElseThrow();
        if (d.isDelivered()) return; // getter correto para boolean

        Payment p = payments.findById(d.getPaymentId()).orElseThrow();
        Merchant m = merchants.findById(p.getMerchantId()).orElseThrow();

        // Monta payload (JSON)
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

    @PostConstruct
    public void startRedeliveryJob() {
        workerPool.scheduler().scheduleAtFixedRate(() -> {
            deliveries.findAll().stream()
                    .filter(d -> !d.isDelivered()) // getter correto para boolean
                    .forEach(d -> scheduleTryDeliver(d.getId(), Math.max(0, d.getAttempts())));
        }, 10, 60, TimeUnit.SECONDS);
    }
}
