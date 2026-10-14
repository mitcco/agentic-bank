package com.bank.transfer.config;

import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import com.bank.transfer.kafka.config.IbanRedactingProducerListener;
import com.bank.transfer.kafka.port.out.kafka.impl.KafkaNotificationSenderOutPortImpl;
import java.time.Duration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.ProducerListener;

@Configuration
class NotificationPublisherConfig {

  private static final Log LOG = LogFactory.getLog(NotificationPublisherConfig.class);

  /**
   * Always present. In spring-boot-kafka 4.1.1 the default LoggingProducerListener is registered with
   * {@code @ConditionalOnMissingBean(ProducerListener.class)}, so any ProducerListener bean replaces it, whatever its
   * type arguments. The auto-configured kafkaTemplate asks for a {@code ProducerListener<Object, Object>} and passes
   * it to the template: this bean is declared with that type for that reason. The behavior with other type
   * arguments has not been verified.
   */
  @Bean
  @SuppressWarnings({"unchecked", "rawtypes"})
  ProducerListener<Object, Object> ibanRedactingProducerListener() {
    return (ProducerListener) new IbanRedactingProducerListener();
  }

  @Bean
  NotificationSenderOutPort kafkaNotificationSenderOutPort(
    KafkaOperations<String, String> kafkaOperations,
    @Value("${notification.topic}") String topic,
    @Value("${notification.publisher.send-timeout}") Duration sendTimeout,
    @Value("${notification.publisher.enabled:false}") String enabled) {

    if (!Boolean.parseBoolean(enabled)) {
      LOG.warn("Outbox publisher DISABLED (notification.publisher.enabled=false): "
        + "notifications stay in table outbox");
    }
    return new KafkaNotificationSenderOutPortImpl(kafkaOperations, topic, sendTimeout);
  }
}
