package edu.ucsal.fiadopay.service;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.plugins.PluginRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentEnrichmentService {
    private final PluginRegistry registry;

    public PaymentEnrichmentService(PluginRegistry registry) {
        this.registry = registry;
    }

    public void enrichByMethod(Payment payment, PaymentRequest req) {
        String method = req.method();
        var plugin = registry.plugin(method)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported method: " + method));
        plugin.enrich(payment, req);
    }
}
