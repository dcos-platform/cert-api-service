package com.dcos.platform.certapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcos.platform.certapi.support.RequiresTestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Guards the completion listener's auto-startup wiring.
 *
 * <p>Tests hold the listener back with {@code cert-api.rabbitmq.completion-listener-auto-startup},
 * so nothing else in the suite would notice if that property stopped reaching the container
 * factory. Production relies on it defaulting to true; were it ignored, the service would start
 * cleanly and silently never consume a completion. This class overrides it to true and checks the
 * container agrees.
 *
 * <p>The context is dirtied deliberately. It is the only test context that attaches a consumer to
 * the shared completions queue without managing it, and a cached context keeps its consumer for the
 * rest of the run — which is the precise fault this property exists to prevent.
 */
@SpringBootTest(properties = "cert-api.rabbitmq.completion-listener-auto-startup=true")
@ActiveProfiles("test")
@RequiresTestDatabase
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Completion listener auto-startup wiring")
class CompletionListenerAutoStartupTest {

    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;

    @Test
    @DisplayName("container auto-starts when the property is enabled, as it is in production")
    void containerAutoStartsWhenPropertyEnabled() {
        assertThat(listenerRegistry.getListenerContainers())
                .as("registered listener containers")
                .isNotEmpty()
                .allSatisfy(container -> assertThat(container.isAutoStartup()).isTrue());
    }
}
