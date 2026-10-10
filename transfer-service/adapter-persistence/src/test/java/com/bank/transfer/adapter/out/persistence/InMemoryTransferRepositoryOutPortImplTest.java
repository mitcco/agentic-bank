package com.bank.transfer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InMemoryTransferRepositoryOutPortImplTest {

  private static final Iban SOURCE = new Iban("IT60X0542811101000000123456");
  private static final Iban DEST = new Iban("IT60X0542811101000000654321");

  private final InMemoryTransferRepositoryOutPortImpl repository = new InMemoryTransferRepositoryOutPortImpl();

  private static Transfer transfer() {
    return Transfer.create(SOURCE, DEST, new Money(new BigDecimal("10"), "EUR"));
  }

  @Test
  void isPreloadedWithDemoTransfers() {
    Iban acc1 = new Iban("IT60X0542811101000000000001");
    Iban acc2 = new Iban("IT60X0542811101000000000002");
    Iban acc3 = new Iban("IT60X0542811101000000000003");

    assertThat(repository.findById("tr1")).contains(
        new Transfer("tr1", acc1, acc2, new Money(new BigDecimal("100.00"), "EUR"), TransferStatusEnum.COMPLETED));
    assertThat(repository.findById("tr2")).contains(
        new Transfer("tr2", acc2, acc3, new Money(new BigDecimal("50.25"), "EUR"), TransferStatusEnum.COMPLETED));
    assertThat(repository.findById("tr3")).contains(
        new Transfer("tr3", acc3, acc1, new Money(new BigDecimal("5000.00"), "EUR"), TransferStatusEnum.REJECTED));
  }

  @Test
  void findByIdReturnsEmptyWhenTransferDoesNotExist() {
    assertThat(repository.findById("missing")).isEmpty();
  }

  @Test
  void saveReturnsTheSavedTransfer() {
    Transfer transfer = transfer();

    assertThat(repository.save(transfer)).isEqualTo(transfer);
  }

  @Test
  void findByIdReturnsSavedTransfer() {
    Transfer transfer = transfer();
    repository.save(transfer);

    assertThat(repository.findById(transfer.id())).contains(transfer);
  }

  @Test
  void saveReplacesTransferWithSameId() {
    Transfer pending = transfer();
    repository.save(pending);
    Transfer completed = pending.complete();

    repository.save(completed);

    assertThat(repository.findById(pending.id())).contains(completed);
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.findById(null)).isInstanceOf(NullPointerException.class);
  }
}
