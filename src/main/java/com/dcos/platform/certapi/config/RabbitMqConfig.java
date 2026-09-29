package com.dcos.platform.certapi.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.ErrorHandler;

@Slf4j
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

    @Value("${cert-api.rabbitmq.completions-queue:certificate.lifecycle.completions}")
    private String completionsQueue;

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
    public Queue completionsQueueBean() {
        return QueueBuilder.durable(completionsQueue).build();
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
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
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

    /**
     * RabbitTemplate configured to publish to the dead-letter exchange. Used by the
     * RepublishMessageRecoverer to send rejected or exhausted messages to the DLX.
     */
    @Bean(name = "dlxRabbitTemplate")
    public RabbitTemplate dlxRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setExchange(dlx);
        template.setRoutingKey(dlqPattern);
        return template;
    }

    /**
     * Message recoverer that republishes rejected or exhausted messages to the dead-letter
     * exchange.
     */
    @Bean
    public RepublishMessageRecoverer republishMessageRecoverer(
            @Qualifier("dlxRabbitTemplate") RabbitTemplate dlxTemplate) {
        return new RepublishMessageRecoverer(dlxTemplate);
    }

    /**
     * Custom error handler that republishes malformed/rejected messages to the DLX before final
     * rejection. Ensures all rejected messages (conversion errors, exhausted retries) are captured
     * in the dead-letter queue for inspection and recovery.
     */
    @Bean
    public ErrorHandler completionListenerErrorHandler(
            RepublishMessageRecoverer republishMessageRecoverer) {
        ConditionalRejectingErrorHandler rejectingHandler =
                new ConditionalRejectingErrorHandler(
                        throwable ->
                                throwable.getCause() instanceof IOException
                                        || MessageConversionException.class.isAssignableFrom(
                                                throwable.getClass()));

        return throwable -> {
            if (throwable instanceof ListenerExecutionFailedException) {
                ListenerExecutionFailedException ex = (ListenerExecutionFailedException) throwable;
                Message failedMessage = ex.getFailedMessage();
                if (failedMessage != null) {
                    log.warn("Republishing failed message to DLX");
                    republishMessageRecoverer.recover(failedMessage, throwable);
                }
            }
            rejectingHandler.handleError(throwable);
        };
    }

    /**
     * Container factory for completion listener. Configures bounded retry with exponential backoff
     * and dead-letter handling. When conversion errors occur (malformed JSON), messages are
     * rejected and republished to the dead-letter exchange via RepublishMessageRecoverer. Exhausted
     * retries are also sent to the DLX.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory completionListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            ErrorHandler completionListenerErrorHandler) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);

        // Retry with exponential backoff: 3 max attempts, initial backoff 1s, multiplier 2
        RetryTemplate retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3);
        retryTemplate.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        factory.setRetryTemplate(retryTemplate);
        factory.setErrorHandler(completionListenerErrorHandler);

        return factory;
    }
}
