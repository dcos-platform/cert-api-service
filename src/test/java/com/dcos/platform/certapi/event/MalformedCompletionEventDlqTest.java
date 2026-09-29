package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@RequiresTestDatabase
@DisplayName("Malformed completion event dead-letter queue tests")
class MalformedCompletionEventDlqTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    private static final String COMPLETIONS_QUEUE = "certificate.lifecycle.completions";
    private static final String DLQ = "cert.events.dlq";

    @AfterEach
    void tearDown() {
        rabbitAdmin.purgeQueue(DLQ);
        rabbitAdmin.purgeQueue(COMPLETIONS_QUEUE);
    }

    @Test
    @DisplayName("malformed JSON is dead-lettered (not silently dropped or retried indefinitely)")
    void malformedJsonDeadLettered() {
        rabbitAdmin.purgeQueue(DLQ);
        rabbitAdmin.purgeQueue(COMPLETIONS_QUEUE);

        String malformedJson = "{invalid json}";
        byte[] messageBytes = malformedJson.getBytes();
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        Message message = new Message(messageBytes, props);

        rabbitTemplate.send(COMPLETIONS_QUEUE, message);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            Object dlqDepthObj =
                                    rabbitAdmin
                                            .getQueueProperties(DLQ)
                                            .get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                            int dlqDepth =
                                    dlqDepthObj != null ? ((Number) dlqDepthObj).intValue() : 0;
                            assertThat(dlqDepth).as("DLQ message count").isGreaterThan(0);
                        });

        Message dlqMessage = rabbitTemplate.receive(DLQ, Duration.ofSeconds(1).toMillis());
        assertThat(dlqMessage).isNotNull();
        assertThat(new String(dlqMessage.getBody())).isEqualTo(malformedJson);
    }
}
