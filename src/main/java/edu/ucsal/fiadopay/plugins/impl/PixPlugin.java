package edu.ucsal.fiadopay.plugins.impl;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.plugins.annotations.PaymentMethod;
import edu.ucsal.fiadopay.plugins.spi.PaymentPlugin;

import java.math.BigDecimal;

@PaymentMethod("PIX")
public class PixPlugin implements PaymentPlugin {
    @Override public String method() { return "PIX"; }

    @Override
    public void enrich(Payment p, PaymentRequest req) {
        if (p == null) return;
        BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
        p.setInstallments(1);
        p.setMonthlyInterest(null);
        p.setTotalWithInterest(amount);
    }
}
