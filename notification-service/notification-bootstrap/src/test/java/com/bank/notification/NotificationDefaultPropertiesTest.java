package com.bank.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

/**
 * Reads the raw {@code application.properties} of the main resources, with the placeholders
 * unresolved, to check the defaults of production. No Spring context is started. The test
 * resources have no file with this name at the root, only {@code config/application.properties}.
 */
class NotificationDefaultPropertiesTest {

  private static Properties properties;

  @BeforeAll
  static void load() throws IOException {
    properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
  }

  @Test
  void kafkaPropertiesHaveTheExpectedDefaults() {
    assertThat(properties.getProperty("spring.kafka.bootstrap-servers"))
        .isEqualTo("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}");
    assertThat(properties.getProperty("spring.kafka.consumer.group-id")).isEqualTo("notification");
    assertThat(properties.getProperty("spring.kafka.consumer.auto-offset-reset"))
        .isEqualTo("earliest");
    assertThat(properties.getProperty("spring.kafka.consumer.enable-auto-commit"))
        .isEqualTo("false");
    assertThat(properties.getProperty("spring.kafka.consumer.key-deserializer"))
        .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
    assertThat(properties.getProperty("spring.kafka.consumer.value-deserializer"))
        .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
  }

  @Test
  void topicIsReadFromTheEnvironmentWithTheContractDefault() {
    assertThat(properties.getProperty("notification.topic"))
        .isEqualTo("${NOTIFICATION_TOPIC:transfer.notifications}");
  }

  @Test
  void mongoUriUsesTheNewPropertyWithNestedPasswordPlaceholder() {
    assertThat(properties.getProperty("spring.mongodb.uri")).isEqualTo(
        "${MONGODB_URI:mongodb://notification:${NOTIFICATION_MONGO_PASSWORD:notification_dev}"
            + "@localhost:27018/notification?authSource=admin}");
    assertThat(properties.stringPropertyNames())
        .noneMatch(name -> name.startsWith("spring.data.mongodb."));
  }

  @Test
  void noAutoConfigurationIsExcluded() {
    assertThat(properties.getProperty("spring.autoconfigure.exclude")).isNull();
  }

  @Test
  void listenerStartsAutomaticallyByDefault() {
    assertThat(properties.getProperty("notification.listener.auto-startup")).isEqualTo("true");
  }
}
