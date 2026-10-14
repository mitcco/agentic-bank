package com.bank.transfer.config;

import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notification.publisher.enabled", havingValue = "true")
class NotificationPublisherSchedulingConfig {

  @Bean
  NotificationPublisherScheduler notificationPublisherScheduler(
    PublishPendingNotificationsInPort publishPendingNotificationsInPort,
    @Value("${notification.publisher.batch-size}") int batchSize) {

    return new NotificationPublisherScheduler(publishPendingNotificationsInPort, batchSize);
  }
}
