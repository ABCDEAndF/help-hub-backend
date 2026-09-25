package org.isolatedareas.helphub.config;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EVENTS_EXCHANGE = "help-hub.events";
    public static final String DEAD_EXCHANGE = "help-hub.dead";
    public static final String NOTIFICATION_QUEUE = "help-hub.notifications";
    public static final String ROUTE_QUEUE = "help-hub.routes";
    public static final String DEAD_QUEUE = "help-hub.dead-letter";

    @Bean DirectExchange eventsExchange() { return new DirectExchange(EVENTS_EXCHANGE, true, false); }
    @Bean DirectExchange deadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean Queue notificationQueue() { return new Queue(NOTIFICATION_QUEUE, true, false, false, deadLetterArguments()); }
    @Bean Queue routeQueue() { return new Queue(ROUTE_QUEUE, true, false, false, deadLetterArguments()); }
    @Bean Queue deadQueue() { return new Queue(DEAD_QUEUE, true); }
    @Bean Binding notificationBinding(Queue notificationQueue, DirectExchange eventsExchange) {
        return BindingBuilder.bind(notificationQueue).to(eventsExchange).with("notification.send");
    }
    @Bean Binding routeBinding(Queue routeQueue, DirectExchange eventsExchange) {
        return BindingBuilder.bind(routeQueue).to(eventsExchange).with("route.compute");
    }
    @Bean Binding deadBinding(Queue deadQueue, DirectExchange deadExchange) {
        return BindingBuilder.bind(deadQueue).to(deadExchange).with("dead");
    }
    private Map<String, Object> deadLetterArguments() {
        return Map.of("x-dead-letter-exchange", DEAD_EXCHANGE, "x-dead-letter-routing-key", "dead");
    }
}
