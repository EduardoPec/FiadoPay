package edu.ucsal.fiadopay.plugins.spi;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;

public interface PaymentPlugin {
    String method();
    void enrich(Payment payment, PaymentRequest req);
}
