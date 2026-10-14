package com.bank.notification.config;

import com.bank.notification.application.port.in.NotifyTransferInPort;
import com.bank.notification.application.port.out.NotificationLogOutPort;
import com.bank.notification.application.port.out.NotificationStoreOutPort;
import com.bank.notification.application.service.NotifyTransferServiceImpl;
import com.bank.notification.kafka.config.NotificationKafkaListenerFactory;
import com.bank.notification.kafka.port.in.kafka.TransferNotificationKafkaListener;
import com.bank.notification.log.port.out.log.impl.NotificationLogOutPortImpl;
import com.bank.notification.mongo.port.out.mongo.impl.NotificationStoreOutPortImpl;
import com.bank.notification.mongo.repository.TransferNotificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Declares the beans of the notification service. It is the only place with {@code @Bean}
 * methods: ports, service, Kafka listener and listener container factory.
 */
@Configuration(proxyBeanMethods = false)
class NotificationConfig {

  @Bean
  NotificationLogOutPort notificationLogOutPort() {
    return new NotificationLogOutPortImpl();
  }

  @Bean
  NotificationStoreOutPort notificationStoreOutPort(TransferNotificationRepository repository) {
    return new NotificationStoreOutPortImpl(repository);
  }

  @Bean
  NotifyTransferInPort notifyTransferInPort(NotificationStoreOutPort store,
      NotificationLogOutPort log) {
    return new NotifyTransferServiceImpl(store, log);
  }

  @Bean
  TransferNotificationKafkaListener transferNotificationKafkaListener(
      NotifyTransferInPort notifyTransfer) {
    return new TransferNotificationKafkaListener(notifyTransfer);
  }

  /**
   * Container factory named {@code kafkaListenerContainerFactory}, the default name used by
   * {@code @KafkaListener}. Auto-startup is read from {@code notification.listener.auto-startup}.
   */
  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      @Value("${notification.listener.auto-startup}") boolean autoStartup) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        NotificationKafkaListenerFactory.containerFactory(consumerFactory);
    factory.setAutoStartup(autoStartup);
    return factory;
  }
}
