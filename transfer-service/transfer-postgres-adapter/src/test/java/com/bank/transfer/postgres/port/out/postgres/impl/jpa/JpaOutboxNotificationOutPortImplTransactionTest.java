package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.domain.TransferStatusEnum;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaOutboxNotificationOutPortImpl.class,
  JpaOutboxNotificationOutPortImplTransactionTest.FixedClockConfig.class})
class JpaOutboxNotificationOutPortImplTransactionTest {

  private static final TransferNotificationDto NOTIFICATION = new TransferNotificationDto(
    "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93", "IT60X0542811101000000000001",
    "IT60X0542811101000000000002", new BigDecimal("100.25"), "EUR",
    TransferStatusEnum.COMPLETED, List.of());

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-10-13T09:30:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired
  private JpaOutboxNotificationOutPortImpl adapter;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanOutbox() {
    jdbcTemplate.update("DELETE FROM outbox");
  }

  // T1
  @Test
  void publishWithoutAnActiveTransactionFailsAndWritesNothing() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

    assertThatThrownBy(() -> adapter.publish(NOTIFICATION))
      .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(count()).isZero();
  }

  // T2
  @Test
  void rowIsCommittedByTheCallerTransaction() {
    new TransactionTemplate(transactionManager).executeWithoutResult(s -> adapter.publish(NOTIFICATION));

    assertThat(count()).isEqualTo(1);
  }

  // T3
  @Test
  void callerRollbackOnlyRemovesTheRow() {
    new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
      adapter.publish(NOTIFICATION);
      s.setRollbackOnly();
    });

    assertThat(count()).isZero();
  }

  // T4 (scenario 16: the transfer save fails after the outbox write)
  @Test
  void callerExceptionRollsTheRowBackAndPropagates() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);

    assertThatThrownBy(() -> template.executeWithoutResult(s -> {
      adapter.publish(NOTIFICATION);
      throw new IllegalStateException("transfer save failed");
    })).isInstanceOf(IllegalStateException.class).hasMessage("transfer save failed");

    assertThat(count()).isZero();
  }

  // T5
  @Test
  void publishJoinsTheCallerTransactionWithoutStartingANewOne() {
    new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
      Object holderBefore = TransactionSynchronizationManager.getResource(entityManagerFactory);
      String nameBefore = TransactionSynchronizationManager.getCurrentTransactionName();
      assertThat(s.isNewTransaction()).isTrue();

      adapter.publish(NOTIFICATION);

      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.getCurrentTransactionName()).isEqualTo(nameBefore);
      assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory))
        .isSameAs(holderBefore);
      // the row must be undone together with the caller, so it was not committed on its own
      s.setRollbackOnly();
    });

    assertThat(count()).isZero();
  }

  private int count() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    assertThat(count).isNotNull();
    return count;
  }
}
