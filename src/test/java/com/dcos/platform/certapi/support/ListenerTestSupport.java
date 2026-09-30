package com.dcos.platform.certapi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

/**
 * Shared helper for integration tests that need a live message listener.
 *
 * <p>Listener auto-startup is disabled globally for tests so that no test consumes from the broker
 * by accident. Tests that genuinely exercise the listener start it here instead.
 *
 * <p>The important detail is that {@code MessageListenerContainer.start()} is asynchronous: it
 * returns before the consumer has attached to the queue, and {@code isRunning()} reports the
 * container's lifecycle flag rather than consumer readiness. Publishing immediately after start can
 * therefore leave a message unconsumed for longer than a test is willing to wait. This helper asks
 * the broker itself whether a consumer is attached, which is the only authoritative signal.
 *
 * <p>Shared state must only be reset while no consumer is attached. Purging the queue or deleting
 * rows underneath a live consumer races against whatever it is currently delivering, which produced
 * redelivery storms, completions dead-lettered against certificates that had just been deleted, and
 * intermittent failures that moved between tests from run to run. Tests therefore stop the
 * container, wait for the broker to confirm the consumer is gone, reset, and only then start again.
 *
 * <p>Neither stop nor start may be trusted to have taken effect when it returns. {@code stop()} is
 * bounded by the container's shutdown timeout rather than synchronous, and {@code isRunning()}
 * reports the container's lifecycle flag rather than consumer state — a {@code if (isRunning())
 * stop()} guard followed by {@code if (!isRunning()) start()} can observe a stale {@code true} and
 * skip the restart entirely, leaving no consumer at all. Both helpers here ask the broker for the
 * queue's consumer count instead, which is the only authoritative signal.
 */
public final class ListenerTestSupport {

    private static final Duration CONSUMER_ATTACH_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONSUMER_DETACH_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private ListenerTestSupport() {}

    /**
     * Starts every registered listener container that is not already running, then blocks until the
     * broker reports at least one consumer attached to the given queue.
     *
     * @param registry the listener registry holding the containers
     * @param rabbitAdmin used to query the broker for the queue's consumer count
     * @param queueName the queue a consumer must be attached to before returning
     */
    public static void startListenersAndAwaitConsumer(
            RabbitListenerEndpointRegistry registry, RabbitAdmin rabbitAdmin, String queueName) {

        var containers = registry.getListenerContainers();
        assertThat(containers).as("no listener containers registered").isNotEmpty();

        containers.stream()
                .filter(container -> !container.isRunning())
                .forEach(container -> container.start());

        await().alias("broker reports a consumer attached to " + queueName)
                .atMost(CONSUMER_ATTACH_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> consumerCount(rabbitAdmin, queueName) > 0);
    }

    /**
     * Stops every registered listener container, then blocks until the broker reports no consumer
     * remaining on the given queue.
     *
     * <p>Call this before purging queues or deleting rows. Returning means the broker has cancelled
     * the consumer, so no delivery is in flight and the reset cannot race one.
     *
     * @param registry the listener registry holding the containers
     * @param rabbitAdmin used to query the broker for the queue's consumer count
     * @param queueName the queue that must have no consumer before returning
     */
    public static void stopListenersAndAwaitNoConsumer(
            RabbitListenerEndpointRegistry registry, RabbitAdmin rabbitAdmin, String queueName) {

        // Stopped unconditionally: isRunning() is not a reliable gate (see class javadoc).
        registry.getListenerContainers().forEach(MessageListenerContainer::stop);

        await().alias("broker reports no consumer left on " + queueName)
                .atMost(CONSUMER_DETACH_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> consumerCount(rabbitAdmin, queueName) == 0);
    }

    private static int consumerCount(RabbitAdmin rabbitAdmin, String queueName) {
        var info = rabbitAdmin.getQueueInfo(queueName);
        return info == null ? 0 : info.getConsumerCount();
    }

    /**
     * Renders the broker's view of the given queues for use in assertion descriptions.
     *
     * <p>A listener test that times out gives no indication of where the message went. Including
     * this in the failure description distinguishes the cases that matter: a message still sitting
     * in the queue means nothing consumed it, a message in the dead-letter queue means the listener
     * rejected it, and a queue that is empty with no consumer means the publish was never routed.
     *
     * @param rabbitAdmin used to query the broker
     * @param queueNames the queues to report on
     * @return a description such as {@code some.queue[messages=0, consumers=1]}, comma separated
     */
    public static String brokerState(RabbitAdmin rabbitAdmin, String... queueNames) {
        return Arrays.stream(queueNames)
                .map(queueName -> describeQueue(rabbitAdmin, queueName))
                .collect(Collectors.joining(", "));
    }

    private static String describeQueue(RabbitAdmin rabbitAdmin, String queueName) {
        var info = rabbitAdmin.getQueueInfo(queueName);
        if (info == null) {
            return queueName + "[absent]";
        }
        return "%s[messages=%d, consumers=%d]"
                .formatted(queueName, info.getMessageCount(), info.getConsumerCount());
    }
}
