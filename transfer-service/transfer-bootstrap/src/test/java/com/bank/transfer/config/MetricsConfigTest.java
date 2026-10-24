package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.micrometer.port.out.micrometer.impl.MicrometerMetricsOutPortImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Wiring della porta delle metriche (piano transfer-14): un solo bean MetricsOutPort, quello Micrometer, costruito
 * sul MeterRegistry del contesto.
 */
class MetricsConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    .withUserConfiguration(MetricsConfig.class)
    .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void startedContext_hasASingleMetricsOutPortOfTheMicrometerType() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeanNamesForType(MetricsOutPort.class)).hasSize(1);
      assertThat(context.getBean(MetricsOutPort.class)).isInstanceOf(MicrometerMetricsOutPortImpl.class);
    });
  }

  @Test
  void startedContext_theSeriesAreAlreadyRegisteredAtZeroInTheRegistryOfTheContext() {
    runner.run(context -> {
      MeterRegistry registry = context.getBean(MeterRegistry.class);

      assertThat(registry.find("bank.transfer.requests").counters()).isNotEmpty();
      assertThat(registry.find("bank.transfer.requests").counters()).allMatch(counter -> counter.count() == 0.0);
      assertThat(registry.find("bank.transfer.refunds").counters()).hasSize(3);
      assertThat(registry.find("bank.transfer.reconciliations").counters()).hasSize(5);
      assertThat(registry.find("bank.transfer.outbox.published").counter()).isNotNull();
      assertThat(registry.find("bank.transfer.outbox.pending").gauge()).isNotNull();
    });
  }

  @Test
  void theBeanWritesOnTheRegistryOfTheContext() {
    runner.run(context -> {
      MeterRegistry registry = context.getBean(MeterRegistry.class);

      context.getBean(MetricsOutPort.class).transferRequested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED);
      context.getBean(MetricsOutPort.class).outboxPending(4);

      assertThat(registry.get("bank.transfer.requests").tag("type", "INTERNAL").tag("status", "COMPLETED")
        .counter().count()).isEqualTo(1.0);
      assertThat(registry.get("bank.transfer.outbox.pending").gauge().value()).isEqualTo(4.0);
    });
  }

  @Test
  void withoutAMeterRegistry_startupFailsInsteadOfUsingAPortThatDoesNothing() {
    new ApplicationContextRunner()
      .withUserConfiguration(MetricsConfig.class)
      .run(context -> assertThat(context).hasFailed());
  }
}
