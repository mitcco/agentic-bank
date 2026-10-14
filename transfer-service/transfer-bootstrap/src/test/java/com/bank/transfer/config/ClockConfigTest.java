package com.bank.transfer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ClockConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
    .withUserConfiguration(ClockConfig.class);

  @Test
  void contextHasASingleUtcClockBean() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
      assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
    });
  }
}
