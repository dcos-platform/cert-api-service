package com.dcos.platform.certapi.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class RabbitMqConfig {

    @Value("${cert-api.rabbitmq.exchange:cert.events}")
    private String exchange;

    @Value("${cert-api.rabbitmq.dlx:cert.events.dlx}")
    private String dlx;

    @Value("${cert-api.rabbitmq.dlq:cert.events.dlq}")
    private String dlq;

    @Value("${cert-api.rabbitmq.orchestrator-queue:certificate.lifecycle.events}")
    private String orchestratorQueue;

    @Value("${cert-api.rabbitmq.admin-queue:cert.lifecycle.events}")
    private String adminQueue;

    @Value("${cert-api.rabbitmq.binding-pattern:cert.#}")
    private String bindingPattern;

    @Value("${cert-api.rabbitmq.dlq-pattern:#}")
    private String dlqPattern;

    @Bean
    public TopicExchange certEventsExchange() {
        return new TopicExchange(exchange, true, false);
    }

    // Dead-letter exchange and queue for undeliverable messages
    @Bean
    public TopicExchange dlExchange() {
        return new TopicExchange(dlx, true, false);
    }

    @Bean
    public Queue dlQueueBean() {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding dlBinding(Queue dlQueueBean) {
        return BindingBuilder.bind(dlQueueBean).to(dlExchange()).with(dlqPattern);
    }

    // Consumer queues declared by the orchestrator and admin service
    @Bean
    public Queue orchestratorQueueBean() {
        return QueueBuilder.durable(orchestratorQueue).build();
    }

    @Bean
    public Queue adminQueueBean() {
        return QueueBuilder.durable(adminQueue).build();
    }

    @Bean
    public Binding orchestratorBinding(Queue orchestratorQueueBean) {
        return BindingBuilder.bind(orchestratorQueueBean)
                .to(certEventsExchange())
                .with(bindingPattern);
    }

    @Bean
    public Binding adminBinding(Queue adminQueueBean) {
        return BindingBuilder.bind(adminQueueBean).to(certEventsExchange()).with(bindingPattern);
    }

    /**
     * Primary ObjectMapper for HTTP API and general application use, configured with camelCase
     * naming (Spring Boot's default) and ISO-8601 timestamps.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper mapper = builder.build();
        mapper.registerModule(new JavaTimeModule());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * ObjectMapper for AMQP message conversion, configured for snake_case serialization and
     * ISO-8601 timestamps. This separate mapper keeps snake_case isolated to the wire format; the
     * HTTP API continues to use camelCase.
     */
    @Bean(name = "amqpObjectMapper")
    public ObjectMapper amqpObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    @Bean
    public MessageConverter jsonMessageConverter(
            @Qualifier("amqpObjectMapper") ObjectMapper amqpObjectMapper) {
        return new Jackson2JsonMessageConverter(amqpObjectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setConfirmCallback(
                (correlation, ack, cause) -> {
                    if (!ack) {
                        throw new RuntimeException("Publisher confirm failed: " + cause);
                    }
                });
        template.setReturnsCallback(
                returned ->
                        new RuntimeException(
                                "Message returned: "
                                        + returned.getMessage()
                                        + " with code "
                                        + returned.getReplyCode()));
        return template;
    }
}
