package edu.ucsal.fiadopay.plugins.spi;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;

public interface AntiFraudRule {
    String name();
    boolean approve(Payment payment, PaymentRequest req);
}
