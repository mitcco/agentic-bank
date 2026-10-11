package com.bank.transfer.persistence.port.out.persistence.impl.inmemory;

import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("inmemory")
public class InMemoryTransferRepositoryOutPortImpl implements TransferRepositoryOutPort {

  private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();

  public InMemoryTransferRepositoryOutPortImpl() {
    loadDemoTransfers();
  }

  @Override
  public Transfer save(Transfer transfer) {
    Objects.requireNonNull(transfer);
    transfers.put(transfer.id(), transfer);
    return transfer;
  }

  public Optional<Transfer> findById(String id) {
    Objects.requireNonNull(id);
    return Optional.ofNullable(transfers.get(id));
  }

  private void loadDemoTransfers() {
    save(demoTransfer("tr1", "IT60X0542811101000000000001", "IT60X0542811101000000000002", "100.00",
        TransferStatusEnum.COMPLETED));
    save(demoTransfer("tr2", "IT60X0542811101000000000002", "IT60X0542811101000000000003", "50.25",
        TransferStatusEnum.COMPLETED));
    save(demoTransfer("tr3", "IT60X0542811101000000000003", "IT60X0542811101000000000001", "5000.00",
        TransferStatusEnum.REJECTED));
  }

  private static Transfer demoTransfer(String id, String source, String dest, String amount,
      TransferStatusEnum status) {
    return new Transfer(id, new Iban(source), new Iban(dest), new Money(new BigDecimal(amount), "EUR"), status);
  }
}
