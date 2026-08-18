package org.openphc.cce.matcher.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.config.KafkaRetryProperties;
import org.openphc.cce.common.kafka.KafkaTopicProperties;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * This configuration replaces Boot's auto-configured Kafka beans, so anything it does not carry over
 * is silently lost. The assertions here are on that carry-over rather than on Spring's own behaviour.
 */
class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @BeforeEach
    void stubTemplateSend() {
        lenient().when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private KafkaTopicProperties topicProperties() {
        KafkaTopicProperties properties = new KafkaTopicProperties();
        properties.setInboundEvents("cce.inbound.events");
        properties.setIntelligenceTriggers("cce.intelligence.triggers");
        properties.setDefaultPartitions(6);
        return properties;
    }

    @Test
    void consumerWrapsBothDeserializersSoAPoisonRecordCannotKillTheContainer() {
        ConsumerFactory<String, Object> factory = config.consumerFactory(new KafkaProperties());

        Map<String, Object> props = factory.getConfigurationProperties();
        assertEquals(ErrorHandlingDeserializer.class,
                props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(ErrorHandlingDeserializer.class,
                props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        // an unwrapped deserializer would throw on the broker thread, where the error handler and
        // the DLQ never see it, and the partition would stall on the same offset forever
        assertEquals(StringDeserializer.class, props.get(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS));
        assertEquals(JsonDeserializer.class, props.get(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS));
    }

    @Test
    void consumerDeserializesByConfiguredTypeRatherThanTrustingTheHeaders() {
        // USE_TYPE_INFO_HEADERS false means an inbound header cannot name the class to instantiate,
        // so a producer outside this system cannot choose the deserialization target.
        Map<String, Object> props = config.consumerFactory(new KafkaProperties())
                .getConfigurationProperties();

        assertEquals(false, props.get(JsonDeserializer.USE_TYPE_INFO_HEADERS));
        assertEquals("org.openphc.cce.common.event.CloudEventMessage",
                props.get(JsonDeserializer.VALUE_DEFAULT_TYPE));
        assertEquals("org.openphc.cce.matcher.kafka.model", props.get(JsonDeserializer.TRUSTED_PACKAGES));
    }

    @Test
    void listenerFactoryCarriesTheYamlsConcurrencyAndAckMode() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.getListener().setConcurrency(4);
        kafkaProperties.getListener().setAckMode(ContainerProperties.AckMode.MANUAL);
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);
        CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory, errorHandler, kafkaProperties);

        assertEquals(ContainerProperties.AckMode.MANUAL, factory.getContainerProperties().getAckMode());
        assertSame(consumerFactory, factory.getConsumerFactory());
        // concurrency has no getter on the factory, so it is read off a container it builds
        assertEquals(4, factory.createContainer("cce.inbound.events").getConcurrency());
    }

    @Test
    void listenerFactoryFallsBackToRecordAckWhenTheYamlIsSilent() {
        // Boot's default is BATCH. Replacing its factory would otherwise silently widen the window in
        // which a crash re-delivers already-processed records.
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.getListener().setAckMode(null);
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = config
                .kafkaListenerContainerFactory(consumerFactory, mock(CommonErrorHandler.class), kafkaProperties);

        assertEquals(ContainerProperties.AckMode.RECORD, factory.getContainerProperties().getAckMode());
    }

    @Test
    void maxAttemptsCountsDeliveriesNotRetries() {
        // FixedBackOff's second argument is a retry count, so max-attempts=3 must mean one delivery
        // plus two retries. Driven through the handler rather than read off the back-off, because the
        // number that matters is how many times a record is actually delivered before it is dead.
        DefaultErrorHandler handler = errorHandler(3, 0L);
        ConsumerRecord<String, String> record = inboundRecord();

        assertFalse(handler.handleOne(new RuntimeException("boom"), record, consumer, container),
                "first failure retries");
        assertFalse(handler.handleOne(new RuntimeException("boom"), record, consumer, container),
                "second failure retries");
        assertTrue(handler.handleOne(new RuntimeException("boom"), record, consumer, container),
                "the third delivery exhausts the budget and the record is recovered");

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void aSingleAttemptMeansNoRetriesRatherThanANegativeCount() {
        // max-attempts=0 must clamp to zero retries; passing -1 through would configure unlimited.
        DefaultErrorHandler handler = errorHandler(0, 0L);

        assertTrue(handler.handleOne(new RuntimeException("boom"), inboundRecord(), consumer, container),
                "the first delivery is the only one");
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void exhaustedRecordsAreRoutedToTheirOwnTopicsDlq() {
        // The destination is derived per record, so one handler serves every topic the service
        // consumes; partition -1 lets the broker choose rather than assuming the DLQ is partitioned
        // like its source.
        DefaultErrorHandler handler = errorHandler(1, 0L);

        handler.handleOne(new RuntimeException("boom"), inboundRecord(), consumer, container);

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertEquals("cce.inbound.events.dlq", captor.getValue().topic());
        assertNull(captor.getValue().partition(), "no partition is pinned");
    }

    @Test
    void producerSerializesWithoutEmbeddingJavaTypeHeaders() {
        // Consumers of these topics are not all Java, so a type header would leak this service's
        // class names into the contract.
        ProducerFactory<String, Object> factory = config.producerFactory(new KafkaProperties());

        Map<String, Object> props = factory.getConfigurationProperties();
        assertEquals(StringSerializer.class, props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(JsonSerializer.class, props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(false, props.get(JsonSerializer.ADD_TYPE_INFO_HEADERS));
    }

    @Test
    void kafkaTemplateUsesTheConfiguredProducerFactory() {
        ProducerFactory<String, Object> producerFactory = config.producerFactory(new KafkaProperties());

        KafkaTemplate<String, Object> template = config.kafkaTemplate(producerFactory);

        assertSame(producerFactory, template.getProducerFactory());
    }

    @Test
    void declaresEveryTopicItReadsOrWritesIncludingTheDlq() {
        KafkaTopicProperties properties = topicProperties();

        NewTopic inbound = config.inboundEventsTopic(properties);
        NewTopic triggers = config.intelligenceTriggersTopic(properties);
        NewTopic dlq = config.inboundEventsDlqTopic(properties);

        assertEquals("cce.inbound.events", inbound.name());
        assertEquals("cce.intelligence.triggers", triggers.name());
        // must match what the recoverer derives, or dead records land on an undeclared topic
        assertEquals("cce.inbound.events.dlq", dlq.name());
        assertEquals(6, inbound.numPartitions());
        assertEquals(6, triggers.numPartitions());
        assertEquals(6, dlq.numPartitions());
    }

    private DefaultErrorHandler errorHandler(long maxAttempts, long backoffMs) {
        KafkaRetryProperties retryProperties = new KafkaRetryProperties();
        retryProperties.setMaxAttempts(maxAttempts);
        retryProperties.setBackoffIntervalMs(backoffMs);
        return (DefaultErrorHandler) config.errorHandler(kafkaTemplate, retryProperties);
    }

    private ConsumerRecord<String, String> inboundRecord() {
        return new ConsumerRecord<>("cce.inbound.events", 0, 42L, "patient-1", "{}");
    }
}
