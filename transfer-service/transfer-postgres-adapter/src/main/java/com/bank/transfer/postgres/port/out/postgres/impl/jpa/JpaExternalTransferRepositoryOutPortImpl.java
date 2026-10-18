package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.postgres.repository.SpringDataExternalTransferRepository;
import com.bank.transfer.postgres.repository.model.ExternalTransferEntity;
import java.util.Objects;
import java.util.Optional;
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

  private static ExternalTransferEntity toEntity(Transfer transfer, String reason) {
    return new ExternalTransferEntity(transfer.id(), transfer.source().value(), transfer.dest().value(),
      transfer.money().amount(), transfer.money().currency(), transfer.status(), reason);
  }

  private static Transfer toDomain(ExternalTransferEntity entity) {
    return new Transfer(entity.getId(), new Iban(entity.getSourceIban()), new Iban(entity.getDestIban()),
      new Money(entity.getAmount(), entity.getCurrency()), entity.getStatus());
  }
}
