package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.port.out.PendingNotificationCountOutPort;
import com.bank.transfer.postgres.repository.SpringDataOutboxRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaPendingNotificationCountOutPortImpl implements PendingNotificationCountOutPort {

  private final SpringDataOutboxRepository repository;

  JpaPendingNotificationCountOutPortImpl(SpringDataOutboxRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public long countPending() {
    return repository.count();
  }
}
