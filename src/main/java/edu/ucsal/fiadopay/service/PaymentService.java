package edu.ucsal.fiadopay.service;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.controller.PaymentResponse;
import edu.ucsal.fiadopay.domain.Merchant;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.plugins.PluginRegistry;
import edu.ucsal.fiadopay.repo.MerchantRepository;
import edu.ucsal.fiadopay.repo.PaymentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private final MerchantRepository merchants;
    private final PaymentRepository payments;
    private final PluginRegistry pluginRegistry;
    private final ThreadPoolTaskExecutor paymentExecutor;
    private final WebhookDispatcher webhookDispatcher;

    @Value("${fiadopay.processing-delay-ms}") long delayMs;
    @Value("${fiadopay.failure-rate}") double failRate;

    public PaymentService(MerchantRepository merchants,
                          PaymentRepository payments,
                          PluginRegistry pluginRegistry,
                          @Qualifier("paymentExecutor")
                          ThreadPoolTaskExecutor paymentExecutor,
                          WebhookDispatcher webhookDispatcher) {
        this.merchants = merchants;
        this.payments = payments;
        this.pluginRegistry = pluginRegistry;
        this.paymentExecutor = paymentExecutor;
        this.webhookDispatcher = webhookDispatcher;
    }

    private Merchant merchantFromAuth(String auth){
        if (auth == null || !auth.startsWith("Bearer FAKE-")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var raw = auth.substring("Bearer FAKE-".length());
        long id;
        try { id = Long.parseLong(raw); }
        catch (NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        var merchant = merchants.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (merchant.getStatus() != Merchant.Status.ACTIVE) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return merchant;
    }

    @Transactional
    public PaymentResponse createPayment(String auth, String idemKey, PaymentRequest req){
        var merchant = merchantFromAuth(auth);
        var mid = merchant.getId();

        if (idemKey != null) {
            var existing = payments.findByIdempotencyKeyAndMerchantId(idemKey, mid);
            if (existing.isPresent()) return toResponse(existing.get());
        }

        String method = req.method() == null ? "" : req.method().toUpperCase();

        var payment = Payment.builder()
                .id("pay_" + UUID.randomUUID().toString().substring(0,8))
                .merchantId(mid)
                .method(method)
                .amount(req.amount())
                .currency(req.currency())
                .installments(req.installments() == null ? 1 : req.installments())
                .status(Payment.Status.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .idempotencyKey(idemKey)
                .metadataOrderId(req.metadataOrderId())
                .build();

        var plugin = pluginRegistry.plugin(method)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported method: " + method));

        plugin.enrich(payment, req);

        boolean approved = pluginRegistry.rules().stream().allMatch(rule -> {
            try { return rule.approve(payment, req); } catch (Exception e) { return false; }
        });
        if (!approved) {
            payment.setStatus(Payment.Status.DECLINED);
            payment.setUpdatedAt(Instant.now());
            payments.save(payment);
            paymentExecutor.submit(() -> processAndWebhook(payment.getId()));
            return toResponse(payment);
        }

        payments.save(payment);
        paymentExecutor.submit(() -> processAndWebhook(payment.getId()));
        return toResponse(payment);
    }

    public PaymentResponse getPayment(String id){
        return toResponse(payments.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Transactional
    public java.util.Map<String,Object> refund(String auth, String paymentId){
        var merchant = merchantFromAuth(auth);
        var p = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!merchant.getId().equals(p.getMerchantId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        p.setStatus(Payment.Status.REFUNDED);
        p.setUpdatedAt(Instant.now());
        payments.save(p);

        paymentExecutor.submit(() -> webhookDispatcher.enqueueDelivery(p.getId()));
        return java.util.Map.of("id","ref_" + UUID.randomUUID(), "status","PENDING");
    }

    private void processAndWebhook(String paymentId){
        try { Thread.sleep(Math.max(0, delayMs)); } catch (InterruptedException ignored) {}

        var p = payments.findById(paymentId).orElse(null);
        if (p == null) return;

        var approved = Math.random() > failRate;
        p.setStatus(approved ? Payment.Status.APPROVED : Payment.Status.DECLINED);
        p.setUpdatedAt(Instant.now());
        payments.save(p);

        webhookDispatcher.enqueueDelivery(p.getId());
    }

    private PaymentResponse toResponse(Payment p){
        return new PaymentResponse(
                p.getId(), p.getStatus().name(), p.getMethod(),
                p.getAmount(), p.getInstallments(), p.getMonthlyInterest(),
                p.getTotalWithInterest()
        );
    }
}
