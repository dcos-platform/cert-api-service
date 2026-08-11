package com.dcos.platform.certapi.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Value("${cert-api.rabbitmq.exchange}")
    private String exchange;

    @Value("${cert-api.rabbitmq.routing-key.created}")
    private String createdKey;

    @Value("${cert-api.rabbitmq.routing-key.renewed}")
    private String renewedKey;

    @Value("${cert-api.rabbitmq.routing-key.revoked}")
    private String revokedKey;

    @Bean
    public TopicExchange certEventsExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue certCreatedQueue() {
        return QueueBuilder.durable("cert.created.queue").build();
    }

    @Bean
    public Queue certRenewedQueue() {
        return QueueBuilder.durable("cert.renewed.queue").build();
    }

    @Bean
    public Queue certRevokedQueue() {
        return QueueBuilder.durable("cert.revoked.queue").build();
    }

    @Bean
    public Binding certCreatedBinding() {
        return BindingBuilder.bind(certCreatedQueue()).to(certEventsExchange()).with(createdKey);
    }

    @Bean
    public Binding certRenewedBinding() {
        return BindingBuilder.bind(certRenewedQueue()).to(certEventsExchange()).with(renewedKey);
    }

    @Bean
    public Binding certRevokedBinding() {
        return BindingBuilder.bind(certRevokedQueue()).to(certEventsExchange()).with(revokedKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
