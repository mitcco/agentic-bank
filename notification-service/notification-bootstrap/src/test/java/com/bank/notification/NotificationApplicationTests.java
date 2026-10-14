package com.bank.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;

@SpringBootTest
class NotificationApplicationTests {

  private static final String WEB_APPLICATION_CONTEXT = "org.springframework.web.context.WebApplicationContext";

  @Autowired
  private Environment environment;

  @Autowired
  private ApplicationContext context;

  @Test
  void contextLoads() {
    // Se il contesto non si carica (per esempio "Failed to configure a DataSource"),
    // il test fallisce prima di arrivare qui.
    assertThat(context).isNotNull();
  }

  @Test
  void applicationNameIsNotification() {
    assertThat(environment.getProperty("spring.application.name")).isEqualTo("notification");
  }

  @Test
  void serverPortIsNotConfigured() {
    assertThat(environment.getProperty("server.port")).isNull();
  }

  @Test
  void noDataSourceBeanIsPresent() {
    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
  }

  // Since phase 07 the Kafka and Mongo auto-configurations are active: the container factory
  // "kafkaListenerContainerFactory" (declared by NotificationConfig) and the single "mongoTemplate"
  // (from Spring Boot) are now expected in the context.
  @Test
  void kafkaListenerContainerFactoryBeanIsPresent() {
    assertThat(context.getBean("kafkaListenerContainerFactory"))
        .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
  }

  @Test
  void mongoTemplateBeanIsPresent() {
    assertThat(context.getBeanNamesForType(MongoTemplate.class)).containsExactly("mongoTemplate");
  }

  @Test
  void contextIsNotAWebApplicationContext() {
    // Con uno starter web Boot rileva SERVLET (o REACTIVE) dal classpath.
    assertThat(WebApplicationType.deduce()).isEqualTo(WebApplicationType.NONE);

    // WebApplicationContext si cerca per nome (spring-web non e' sul classpath, quindi non si puo'
    // importare). Se arrivasse, l'assegnabilita' copre tutta la gerarchia, non solo le interfacce dirette.
    ClassLoader classLoader = context.getClass().getClassLoader();
    if (ClassUtils.isPresent(WEB_APPLICATION_CONTEXT, classLoader)) {
      assertThat(ClassUtils.resolveClassName(WEB_APPLICATION_CONTEXT, classLoader)
          .isAssignableFrom(context.getClass())).isFalse();
    }
  }

}
