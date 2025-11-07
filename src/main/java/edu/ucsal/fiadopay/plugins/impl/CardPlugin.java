package edu.ucsal.fiadopay.plugins.impl;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;
import edu.ucsal.fiadopay.plugins.annotations.AntiFraud;
import edu.ucsal.fiadopay.plugins.annotations.PaymentMethod;
import edu.ucsal.fiadopay.plugins.spi.AntiFraudRule;
import edu.ucsal.fiadopay.plugins.spi.PaymentPlugin;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@PaymentMethod("CARD")
@AntiFraud(name = "HighAmount", threshold = 1000.00)
public class CardPlugin implements PaymentPlugin, AntiFraudRule {
    @Override public String method() { return "CARD"; }

    @Override
    public void enrich(Payment p, PaymentRequest req) {
        if (p == null) return;
        BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
        Integer n = p.getInstallments();

        Double interestRate = null;
        BigDecimal total = amount;

        if (n != null && n > 1) {
            interestRate = 1.0;
            var base = new BigDecimal("1.01");
            var factor = base.pow(n);
            total = amount.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }

        p.setMonthlyInterest(interestRate);
        p.setTotalWithInterest(total);
    }

    @Override public String name() { return "HighAmount"; }

    @Override
    public boolean approve(Payment p, PaymentRequest req) {
        if (p == null || p.getAmount() == null) return true;
        double threshold = 1000.00;
        return p.getAmount().doubleValue() <= threshold;
    }
}
