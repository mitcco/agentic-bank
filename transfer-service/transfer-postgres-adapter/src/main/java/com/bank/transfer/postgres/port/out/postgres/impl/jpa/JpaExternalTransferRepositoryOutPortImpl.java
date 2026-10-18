package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.postgres.repository.SpringDataExternalTransferRepository;
import com.bank.transfer.postgres.repository.model.ExternalTransferEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
public class JpaExternalTransferRepositoryOutPortImpl implements ExternalTransferRepositoryOutPort {

  private final SpringDataExternalTransferRepository repository;

  JpaExternalTransferRepositoryOutPortImpl(SpringDataExternalTransferRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(Transfer transfer, String reason) {
    Objects.requireNonNull(transfer);
    repository.save(toEntity(transfer, reason));
  }

  @Override
  public Optional<Transfer> findByIdForUpdate(String id) {
    Objects.requireNonNull(id);
    return repository.findByIdForUpdate(id).map(JpaExternalTransferRepositoryOutPortImpl::toDomain);
  }

  @Override
  public List<String> findInProgressIdsCreatedBefore(Instant threshold, int max) {
    Objects.requireNonNull(threshold, "threshold");
    if (max < 1) {
      throw new IllegalArgumentException("max must be at least 1");
    }
    LocalDateTime utcThreshold = LocalDateTime.ofInstant(threshold, ZoneOffset.UTC);
    return repository.findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
      EnumSet.of(TransferStatusEnum.SENT, TransferStatusEnum.UNKNOWN), utcThreshold, Limit.of(max))
             .stream().map(ExternalTransferEntity::getId).toList();
  }

  private static ExternalTransferEntity toEntity(Transfer transfer, String reason) {
    return new ExternalTransferEntity(transfer.id(), transfer.source().value(), transfer.dest().value(),
      transfer.money().amount(), transfer.money().currency(), transfer.status(), reason);
  }

  private static Transfer toDomain(ExternalTransferEntity entity) {
    return new Transfer(entity.getId(), new Iban(entity.getSourceIban()), new Iban(entity.getDestIban()),
      new Money(entity.getAmount(), entity.getCurrency()), entity.getStatus());
  }
}
