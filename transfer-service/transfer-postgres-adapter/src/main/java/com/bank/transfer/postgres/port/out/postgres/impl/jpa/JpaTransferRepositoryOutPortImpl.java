package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.postgres.repository.SpringDataTransferRepository;
import com.bank.transfer.postgres.repository.model.TransferEntity;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTransferRepositoryOutPortImpl implements TransferRepositoryOutPort {

  private final SpringDataTransferRepository repository;

  JpaTransferRepositoryOutPortImpl(SpringDataTransferRepository repository) {
    this.repository = repository;
  }

  @Override
  public Transfer save(Transfer transfer) {
    Objects.requireNonNull(transfer);
    return toDomain(repository.save(toEntity(transfer)));
  }

  public Optional<Transfer> findById(String id) {
    Objects.requireNonNull(id);
    return repository.findById(id).map(JpaTransferRepositoryOutPortImpl::toDomain);
  }

  private static TransferEntity toEntity(Transfer transfer) {
    return new TransferEntity(transfer.id(), transfer.source().value(), transfer.dest().value(),
      transfer.money().amount(), transfer.money().currency(), transfer.status());
  }

  private static Transfer toDomain(TransferEntity entity) {
    return new Transfer(entity.getId(), new Iban(entity.getSourceIban()), new Iban(entity.getDestIban()),
      new Money(entity.getAmount(), entity.getCurrency()), entity.getStatus());
  }
}
