package com.bank.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.notification.application.port.in.NotifyTransferInPort;
import com.bank.notification.application.port.out.NotificationLogOutPort;
import com.bank.notification.application.port.out.NotificationStoreOutPort;
import com.bank.notification.application.service.NotifyTransferServiceImpl;
import com.bank.notification.kafka.port.in.kafka.TransferNotificationKafkaListener;
import com.bank.notification.log.port.out.log.impl.NotificationLogOutPortImpl;
import com.bank.notification.mongo.port.out.mongo.impl.NotificationStoreOutPortImpl;
import com.bank.notification.mongo.repository.TransferNotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Collection;

/**
 * Checks the wiring declared by {@code NotificationConfig} and the Spring Boot auto-configurations
 * on the real context. The listener is switched off by {@code config/application.properties} of
 * the tests, so no broker and no MongoDB are needed.
 */
@SpringBootTest
class NotificationConfigTest {

  private static final String STRING_DESERIALIZER =
      "org.apache.kafka.common.serialization.StringDeserializer";

  @Autowired
  private ApplicationContext context;

  @Autowired
  private Environment environment;

  @Test
  void exposesExactlyOneBeanForEachPortWithTheExpectedImplementation() {
    assertThat(context.getBeansOfType(NotifyTransferInPort.class).values())
        .hasSize(1).first().isInstanceOf(NotifyTransferServiceImpl.class);
    assertThat(context.getBeansOfType(NotificationStoreOutPort.class).values())
        .hasSize(1).first().isInstanceOf(NotificationStoreOutPortImpl.class);
    assertThat(context.getBeansOfType(NotificationLogOutPort.class).values())
        .hasSize(1).first().isInstanceOf(NotificationLogOutPortImpl.class);
  }

  @Test
  void registersExactlyOneKafkaListenerBean() {
    assertThat(context.getBeansOfType(TransferNotificationKafkaListener.class)).hasSize(1);
  }

  @Test
  void declaresASingleContainerFactoryWithRecordAckAndTheSharedConsumerFactory() {
    assertThat(context.getBeansOfType(ConcurrentKafkaListenerContainerFactory.class))
        .hasSize(1).containsOnlyKeys("kafkaListenerContainerFactory");
    ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(
        "kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);

    assertThat(factory.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.RECORD);
    assertThat(factory.getConsumerFactory()).isSameAs(context.getBean(ConsumerFactory.class));
  }

  @Test
  void registersExactlyOneStoppedListenerContainerOnTheConfiguredTopic() {
    KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);

    Collection<MessageListenerContainer> containers = registry.getListenerContainers();

    assertThat(containers).hasSize(1);
    MessageListenerContainer container = containers.iterator().next();
    assertThat(container.getContainerProperties().getTopics())
        .containsExactly(environment.getProperty("notification.topic"));
    assertThat(container.isRunning()).isFalse();
  }

  @Test
  void buildsTheConsumerFromTheKafkaConsumerProperties() {
    ConsumerFactory<?, ?> consumerFactory = context.getBean(ConsumerFactory.class);

    var properties = consumerFactory.getConfigurationProperties();

    assertThat(String.valueOf(properties.get("group.id")))
        .isEqualTo(environment.getProperty("spring.kafka.consumer.group-id"));
    assertThat(String.valueOf(properties.get("auto.offset.reset"))).isEqualTo("earliest");
    assertThat(String.valueOf(properties.get("enable.auto.commit"))).isEqualTo("false");
    assertThat(nameOf(properties.get("key.deserializer"))).isEqualTo(STRING_DESERIALIZER);
    assertThat(nameOf(properties.get("value.deserializer"))).isEqualTo(STRING_DESERIALIZER);
  }

  @Test
  void findsTheMongoRepositoryByScanning() {
    assertThat(context.getBeansOfType(TransferNotificationRepository.class)).hasSize(1);
  }

  @Test
  void usesTheTestDatabaseAndNeverTheRealOne() {
    MongoTemplate template = context.getBean(MongoTemplate.class);

    assertThat(template.getDb().getName()).isEqualTo("notification_test")
        .isNotEqualTo("notification");
  }

  private static String nameOf(Object value) {
    return value instanceof Class<?> type ? type.getName() : String.valueOf(value);
  }
}
