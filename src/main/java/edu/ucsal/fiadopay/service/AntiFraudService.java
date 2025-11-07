package edu.ucsal.fiadopay.service;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.plugins.PluginRegistry;
import org.springframework.stereotype.Service;

@Service
public class AntiFraudService {
    private final PluginRegistry registry;

    public AntiFraudService(PluginRegistry registry) {
        this.registry = registry;
    }

    public boolean approve(Payment payment, PaymentRequest req) {
        return registry.rules().stream().allMatch(r -> {
            try {
                return r.approve(payment, req);
            }
            catch (Exception e) {
                return false;
            }
        });
    }
}