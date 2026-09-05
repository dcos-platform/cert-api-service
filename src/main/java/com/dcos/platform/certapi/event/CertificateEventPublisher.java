package com.dcos.platform.certapi.event;

import com.dcos.platform.certapi.domain.Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CertificateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CertificateEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${cert-api.rabbitmq.exchange}")
    private String exchange;

    @Value("${cert-api.rabbitmq.routing-key.created}")
    private String createdKey;

    @Value("${cert-api.rabbitmq.routing-key.renewed}")
    private String renewedKey;

    @Value("${cert-api.rabbitmq.routing-key.revoked}")
    private String revokedKey;

    public CertificateEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishCreated(Certificate cert) {
        publish(createdKey, "CREATED", cert);
    }

    public void publishRenewed(Certificate cert) {
        publish(renewedKey, "RENEWED", cert);
    }

    public void publishRevoked(Certificate cert) {
        publish(revokedKey, "REVOKED", cert);
    }

    private void publish(String routingKey, String eventType, Certificate cert) {
        CertificateEvent event =
                new CertificateEvent(
                        eventType,
                        cert.getId(),
                        cert.getSubject(),
                        cert.getType(),
                        cert.getStatus().name());
        log.info("Publishing {} event for certificate {}", eventType, cert.getId());
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
    }
}
