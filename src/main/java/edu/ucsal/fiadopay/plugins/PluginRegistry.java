package edu.ucsal.fiadopay.plugins;

import edu.ucsal.fiadopay.plugins.annotations.PaymentMethod;
import edu.ucsal.fiadopay.plugins.spi.AntiFraudRule;
import edu.ucsal.fiadopay.plugins.spi.PaymentPlugin;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class PluginRegistry {

    private final Map<String, PaymentPlugin> byMethod;
    private final List<AntiFraudRule> rules;

    public PluginRegistry(String beanFactory) {
        Map<String, Object> beans = beanFactory.getBeansWithAnnotation(PaymentMethod.class);

        Map<String, PaymentPlugin> tmpByMethod = new HashMap<>();
        List<AntiFraudRule> tmpRules = new ArrayList<>();

        for (Object o : beans.values()) {
            if (o instanceof PaymentPlugin pp) {
                PaymentMethod ann = o.getClass().getAnnotation(PaymentMethod.class);
                if (ann != null) {
                    tmpByMethod.put(ann.value(), pp);
                }
            }
            if (o instanceof AntiFraudRule ar) {
                tmpRules.add(ar);
            }
        }

        this.byMethod = Collections.unmodifiableMap(tmpByMethod);
        this.rules = Collections.unmodifiableList(tmpRules);
    }

    public Optional<PaymentPlugin> plugin(String method) {
        return Optional.ofNullable(byMethod.get(method));
    }

    public List<AntiFraudRule> rules() {
        return rules;
    }

    public Set<String> supportedMethods() {
        return byMethod.keySet();
    }
}

