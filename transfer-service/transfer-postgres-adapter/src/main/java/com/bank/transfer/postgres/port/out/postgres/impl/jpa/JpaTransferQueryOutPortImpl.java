package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.dto.StoredTransferDto;
import com.bank.transfer.application.port.out.TransferQueryOutPort;
import com.bank.transfer.postgres.repository.SpringDataExternalTransferRepository;
import com.bank.transfer.postgres.repository.SpringDataTransferRepository;
import com.bank.transfer.postgres.repository.model.ExternalTransferEntity;
import com.bank.transfer.postgres.repository.model.TransferEntity;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTransferQueryOutPortImpl implements TransferQueryOutPort {

  private final SpringDataTransferRepository transferRepository;
  private final SpringDataExternalTransferRepository externalTransferRepository;

  JpaTransferQueryOutPortImpl(SpringDataTransferRepository transferRepository,
    SpringDataExternalTransferRepository externalTransferRepository) {
    this.transferRepository = transferRepository;
    this.externalTransferRepository = externalTransferRepository;
  }

  /** Internal transfers first, then external ones; no lock. */
  @Override
  public Optional<StoredTransferDto> findById(String transferId) {
    Objects.requireNonNull(transferId, "transferId");
    return transferRepository.findById(transferId).map(JpaTransferQueryOutPortImpl::toDto)
      .or(() -> externalTransferRepository.findById(transferId).map(JpaTransferQueryOutPortImpl::toDto));
  }

  private static StoredTransferDto toDto(TransferEntity entity) {
    return new StoredTransferDto(entity.getId(), entity.getSourceIban(), entity.getDestIban(), entity.getAmount(),
      entity.getCurrency(), entity.getStatus(), null);
  }

  private static StoredTransferDto toDto(ExternalTransferEntity entity) {
    return new StoredTransferDto(entity.getId(), entity.getSourceIban(), entity.getDestIban(), entity.getAmount(),
      entity.getCurrency(), entity.getStatus(), entity.getReason());
  }
}
