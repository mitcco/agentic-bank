package com.bank.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
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

  // I test sui bean "kafkaTemplate" e "mongoTemplate" (per nome, perche' le classi non sono sul
  // classpath in questa fase) andranno rivisti con una modifica esplicita nelle fasi 05-07,
  // quando i bean ci saranno davvero.
  @Test
  void noKafkaTemplateBeanIsPresent() {
    assertThat(context.containsBean("kafkaTemplate")).isFalse();
  }

  @Test
  void noMongoTemplateBeanIsPresent() {
    assertThat(context.containsBean("mongoTemplate")).isFalse();
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
