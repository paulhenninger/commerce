package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.EventCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Wires the outbox, the idempotent dispatcher and the retry/dead-letter policy
 * into any service that has a database and Kafka.
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcClientAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
        KafkaAutoConfiguration.class})
@ConditionalOnBean({DataSource.class, KafkaTemplate.class})
@EnableScheduling
@org.springframework.context.annotation.Import(DatabaseUnavailableHandler.class)
public class MessagingAutoConfiguration {

    @Bean
    OutboxMetrics outboxMetrics(DataSource dataSource, MeterRegistry registry) {
        return new OutboxMetrics(dataSource, registry);
    }

    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, KafkaTemplate<?, ?> template, OutboxMetrics metrics,
                            Environment env) {
        return new OutboxRelay(dataSource, bytes(template), metrics, applicationName(env));
    }

    @Bean
    Outbox outbox(JdbcClient jdbc, EventCodec codec, ObjectProvider<Tracer> tracer, OutboxRelay relay,
                  Environment env) {
        return new Outbox(jdbc, codec, tracer, relay, applicationName(env));
    }

    @Bean
    EventDispatcher eventDispatcher(EventCodec codec, JdbcClient jdbc, PlatformTransactionManager transactions,
                                    MeterRegistry registry) {
        return new EventDispatcher(codec, jdbc, new TransactionTemplate(transactions), registry);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template, MeterRegistry registry, Environment env) {
        return KafkaErrorHandling.errorHandler(bytes(template), registry, applicationName(env));
    }

    @Bean
    TraceContinuation traceContinuation(ObjectProvider<Tracer> tracer,
                                        ObjectProvider<io.micrometer.tracing.propagation.Propagator> propagator) {
        return new TraceContinuation(tracer, propagator);
    }

    @Bean
    ProcessedEventsPurge processedEventsPurge(JdbcClient jdbc) {
        return new ProcessedEventsPurge(jdbc);
    }

    private static String applicationName(Environment env) {
        return env.getRequiredProperty("spring.application.name");
    }

    /** Boot's template is untyped; messaging-defaults.yml configures String keys and byte[] values. */
    @SuppressWarnings("unchecked")
    private static KafkaOperations<String, byte[]> bytes(KafkaTemplate<?, ?> template) {
        return (KafkaOperations<String, byte[]>) template;
    }
}
