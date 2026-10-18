package com.bank.transfer.postgres.port.out.postgres.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaAccountRepositoryOutPortImpl;
import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaExternalTransferRepositoryOutPortImpl;
import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaTransferRepositoryOutPortImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * With transfer.demo-data.enabled=false the seeder bean is not created, so nothing is loaded.
 */
@DataJpaTest(properties = "transfer.demo-data.enabled=false")
@Import({JpaAccountRepositoryOutPortImpl.class, JpaTransferRepositoryOutPortImpl.class,
  JpaExternalTransferRepositoryOutPortImpl.class, DemoDataSeeder.class})
class DemoDataSeedingDisabledTest {

  @Autowired
  private ApplicationContext context;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private int count(String table) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    assertThat(count).isNotNull();
    return count;
  }

  @Test
  void noSeederAndNoDataWhenDisabled() {
    assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
    assertThat(count("account")).isZero();
    assertThat(count("transfer")).isZero();
    assertThat(count("external_transfer")).isZero();
  }
}
