package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.dto.PendingNotificationDto;
import com.bank.transfer.application.exception.InvalidOutboxRowException;
import com.bank.transfer.application.port.out.PendingNotificationOutPort;
import com.bank.transfer.postgres.repository.SpringDataOutboxRepository;
import com.bank.transfer.postgres.repository.model.OutboxEntity;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaPendingNotificationOutPortImpl implements PendingNotificationOutPort {

  private final SpringDataOutboxRepository repository;

  JpaPendingNotificationOutPortImpl(SpringDataOutboxRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<PendingNotificationDto> findPending(int maxRows) {
    if (maxRows < 1) {
      throw new IllegalArgumentException("maxRows must be at least 1");
    }
    return repository.findAllByOrderByIdAsc(Limit.of(maxRows)).stream()
      .map(JpaPendingNotificationOutPortImpl::toDto)
      .toList();
  }

  private static PendingNotificationDto toDto(OutboxEntity entity) {
    try {
      return new PendingNotificationDto(entity.getId(), entity.getTransferId(), entity.getPayload());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidOutboxRowException(entity.getId());
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void delete(long id) {
    repository.deleteById(id);
  }
}
