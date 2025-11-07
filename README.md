# FiadoPay Simulator (Spring Boot + H2)

Gateway de pagamento **FiadoPay** para a AVI/POOA.
Substitui PSPs reais com um backend em memória (H2).

## Rodar
```bash
./mvnw spring-boot:run
# ou
mvn spring-boot:run
```

H2 console: http://localhost:8080/h2  
Swagger UI: http://localhost:8080/swagger-ui.html

## Fluxo

1) **Cadastrar merchant**
```bash
curl -X POST http://localhost:8080/fiadopay/admin/merchants \
 -H "Content-Type: application/json" \
 -d '{"name":"Minha Loja Online","webhookUrl":"http://localhost:9000/webhook-sink"}'
```

2) **Obter token**
```bash
curl -X POST http://localhost:8080/fiadopay/auth/token \
 -H "Content-Type: application/json" \
 -d '{"client_id":"<clientId>","client_secret":"<clientSecret>"}'
```

3) **Criar pagamento**
```bash
curl -X POST http://localhost:8080/fiadopay/gateway/payments \
 -H "Authorization: Bearer FAKE-<merchantId>" \
 -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
 -H "Content-Type: application/json" \
 -d '{
     "method":"CARD",
     "currency":"BRL",
     "amount":1000.00,
     "installments":3,
     "metadataOrderId":"ORD-J24"
     }'
```

4) **Consultar pagamento**
```bash
curl http://localhost:8080/fiadopay/gateway/payments/<paymentId>
```

## Contexto e Objetivo

O FiadoPay foi projetado para **simular o comportamento de um gateway real de pagamentos**, permitindo que lojas (merchants) processem transações de maneira fictícia, com:
- Persistência em **H2 (memória)**
- **Plugins dinâmicos** de métodos de pagamento (`CARD`, `PIX`)
- **Regras antifraude** com thresholds configuráveis
- **Webhook assíncrono** e assinado (HMAC-SHA256)
- Processamento de **pagamentos e reembolsos**

---

## Decisões de Design

| Decisão | Justificativa |
|----------|----------------|
| **Arquitetura modular baseada em anotações** | Permite adicionar novos métodos de pagamento e regras antifraude sem alterar o core |
| **Spring Boot + H2** | Simplifica a configuração e torna o projeto executável de forma isolada |
| **ThreadPoolTaskExecutor** | Substitui o uso manual de `ExecutorService` para seguir boas práticas do Spring |
| **TaskScheduler** | Gerencia retentativas automáticas de webhooks com backoff exponencial |
| **HttpClient nativo (Java 11+)** | Evita dependências externas e facilita assinatura HMAC |
| **Padrão Repository (Spring Data JPA)** | Abstrai persistência e simplifica consultas |

---

## Anotações Criadas e Metadados

| Anotação | Função | Metadados |
|-----------|--------|-----------|
| `@PaymentMethod` | Marca classes que implementam métodos de pagamento | Valor: `"PIX"`, `"CARD"` |
| `@AntiFraud` | Marca classes com lógica antifraude | `name`, `threshold` |
| `@WebhookSink` | (Meta) marca classes ou métodos que recebem notificações de webhook | `eventType`, `targetUrl` |

Essas anotações são detectadas automaticamente via reflexão pelo `PluginRegistry`, que registra dinamicamente todos os plugins e regras antifraude disponíveis

---

## Mecanismo de Reflexão

A classe `PluginRegistry` usa **Spring BeanFactory** e reflexão para:
- Localizar beans anotados com `@PaymentMethod` e `@AntiFraud`
- Mapear cada método de pagamento (`CARD`, `PIX`) para seu respectivo plugin
- Registrar instâncias de `AntiFraudRule` para aplicação nas transações

Essa abordagem segue o padrão Service Discovery, permitindo adicionar novos plugins sem alterar o código-fonte principal

---

## Threads e Concorrência

O sistema utiliza **duas camadas de concorrência**:

| Componente | Função | Implementação |
|-------------|--------|----------------|
| `ThreadPoolTaskExecutor` | Processamento assíncrono de pagamentos e webhooks | Bean configurado via `AsyncConfig` |
| `TaskScheduler` | Retentativas automáticas com backoff exponencial | Invocado por `WebhookDispatcher.scheduleTryDeliver()` |

Além disso, a entrega de webhooks ocorre de forma não bloqueante, com retentativas crescentes até 30 segundos

---

## Padrões Aplicados

| Padrão | Onde foi aplicado | Benefício |
|---------|--------------------|------------|
| **Strategy** | `PaymentPlugin` e `AntiFraudRule` | Permite diferentes estratégias de pagamento e validação antifraude |
| **Template Method** | `PaymentService` | Padroniza o fluxo de criação e processamento de pagamentos |
| **Observer/Event** | `WebhookDispatcher` | Notifica merchants sobre mudanças no status de pagamento |
| **Repository** | `MerchantRepository`, `PaymentRepository`, `WebhookDeliveryRepository` | Separa persistência da lógica de negócio |
| **Command** | Tarefas assíncronas do `ThreadPoolTaskExecutor` | Execução desacoplada e reagendável |

---

## Limites Conhecidos

- **Sem autenticação real**: o token é apenas `Bearer FAKE-{merchantId}` 
- **Banco em memória (H2)**: dados são perdidos a cada reinício  
- **Plugins fixos**: apenas `PIX` e `CARD` estão implementados  
- **Sem front-end**: o consumo deve ser feito via `curl` ou Swagger  
- **Assinatura HMAC** usa segredo único (`ucsal-2025`)

---

## Prints
