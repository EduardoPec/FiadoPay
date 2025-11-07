package edu.ucsal.fiadopay.plugins.impl;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.plugins.annotations.AntiFraud;
import edu.ucsal.fiadopay.plugins.annotations.PaymentMethod;
import edu.ucsal.fiadopay.plugins.spi.AntiFraudRule;
import edu.ucsal.fiadopay.plugins.spi.PaymentPlugin;

import java.math.BigDecimal;

@PaymentMethod("CARD")
@AntiFraud(name = "HighAmount", threshold = 1000.00)
public class CardPlugin implements PaymentPlugin, AntiFraudRule {
    @Override public String method() { return "CARD"; }

    @Override
    public void enrich(Payment p, PaymentRequest req) {
        if (p == null) return;
        BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
        Integer n = p.getInstallments();
        BigDecimal i = BigDecimal.valueOf(p.getMonthlyInterest());

        if (n != null && n > 1) {
            BigDecimal total = amount.multiply(BigDecimal.ONE.add(i).pow(n));
            p.setTotalWithInterest(total);
        } else {
            p.setTotalWithInterest(amount);
        }
    }

    @Override public String name() { return "HighAmount"; }

    @Override
    public boolean approve(Payment p, PaymentRequest req) {
        if (p == null || p.getAmount() == null) return true;
        double threshold = 1000.00;
        return p.getAmount().doubleValue() <= threshold;
    }
}
