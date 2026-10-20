package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.StoredTransferDto;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({
  JpaTransferQueryOutPortImpl.class,
  JpaAccountRepositoryOutPortImpl.class,
  JpaTransferRepositoryOutPortImpl.class,
  JpaExternalTransferRepositoryOutPortImpl.class
})
class JpaTransferQueryOutPortImplTest {

  private static final Iban SOURCE = new Iban("IT60X0542811101000000777001");
  private static final Iban DEST = new Iban("IT60X0542811101000000777002");
  private static final Iban EXTERNAL = new Iban("IT60X0306909606100000012345");
  private static final String INTERNAL_ID = "q-internal-1";
  private static final String EXTERNAL_ID = "q-external-1";

  @Autowired
  private JpaTransferQueryOutPortImpl queryPort;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accountRepository;

  @Autowired
  private JpaTransferRepositoryOutPortImpl transferRepository;

  @Autowired
  private JpaExternalTransferRepositoryOutPortImpl externalTransferRepository;

  @Autowired
  private EntityManager entityManager;

  /** transfer.source_iban and dest_iban reference account.iban; external_transfer only the source. */
  @BeforeEach
  void createAccounts() {
    accountRepository.save(new Account("q-acc-source", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    accountRepository.save(new Account("q-acc-dest", DEST, new Money(new BigDecimal("0"), "EUR")));
    flushAndClear();
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private static Transfer internal(TransferStatusEnum status) {
    return new Transfer(INTERNAL_ID, SOURCE, DEST, new Money(new BigDecimal("10.50"), "EUR"), status);
  }

  private static Transfer external(TransferStatusEnum status) {
    return new Transfer(EXTERNAL_ID, SOURCE, EXTERNAL, new Money(new BigDecimal("25.75"), "EUR"), status);
  }

  @Test
  void findsAnInternalTransferWithItsFields() {
    transferRepository.save(internal(TransferStatusEnum.COMPLETED));
    flushAndClear();

    StoredTransferDto stored = queryPort.findById(INTERNAL_ID).orElseThrow();

    assertThat(stored.transferId()).isEqualTo(INTERNAL_ID);
    assertThat(stored.sourceIban()).isEqualTo(SOURCE.value());
    assertThat(stored.destIban()).isEqualTo(DEST.value());
    assertThat(stored.amount()).isEqualByComparingTo("10.50");
    assertThat(stored.currency()).isEqualTo("EUR");
    assertThat(stored.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(stored.reason()).isNull();
  }

  @Test
  void findsAnInternalRejectedTransferWithoutReason() {
    transferRepository.save(internal(TransferStatusEnum.REJECTED));
    flushAndClear();

    StoredTransferDto stored = queryPort.findById(INTERNAL_ID).orElseThrow();

    assertThat(stored.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(stored.reason()).isNull();
  }

  @Test
  void findsAnExternalTransferWithItsReason() {
    Transfer rejected = external(TransferStatusEnum.SENT).rejectAfterSent();
    externalTransferRepository.save(rejected, "Rejected by the clearing house");
    flushAndClear();

    StoredTransferDto stored = queryPort.findById(EXTERNAL_ID).orElseThrow();

    assertThat(stored.transferId()).isEqualTo(EXTERNAL_ID);
    assertThat(stored.sourceIban()).isEqualTo(SOURCE.value());
    assertThat(stored.destIban()).isEqualTo(EXTERNAL.value());
    assertThat(stored.amount()).isEqualByComparingTo("25.75");
    assertThat(stored.currency()).isEqualTo("EUR");
    assertThat(stored.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(stored.reason()).isEqualTo("Rejected by the clearing house");
  }

  @Test
  void findsAnExternalSentTransfer() {
    externalTransferRepository.save(external(TransferStatusEnum.SENT), null);
    flushAndClear();

    StoredTransferDto stored = queryPort.findById(EXTERNAL_ID).orElseThrow();

    assertThat(stored.status()).isEqualTo(TransferStatusEnum.SENT);
    assertThat(stored.reason()).isNull();
  }

  @Test
  void unknownIdIsEmpty() {
    transferRepository.save(internal(TransferStatusEnum.COMPLETED));
    externalTransferRepository.save(external(TransferStatusEnum.SENT), null);
    flushAndClear();

    assertThat(queryPort.findById("q-missing")).isEmpty();
  }

  @Test
  void readsWithoutLocking() {
    transferRepository.save(internal(TransferStatusEnum.COMPLETED));
    externalTransferRepository.save(external(TransferStatusEnum.SENT), null);
    flushAndClear();

    queryPort.findById(INTERNAL_ID).orElseThrow();
    queryPort.findById(EXTERNAL_ID).orElseThrow();

    Object internalEntity = entityManager.createQuery("select t from TransferEntity t").getSingleResult();
    Object externalEntity = entityManager.createQuery("select t from ExternalTransferEntity t").getSingleResult();
    assertThat(entityManager.getLockMode(internalEntity)).isNotEqualTo(LockModeType.PESSIMISTIC_WRITE);
    assertThat(entityManager.getLockMode(externalEntity)).isNotEqualTo(LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  void nullIdIsRefused() {
    assertThatThrownBy(() -> queryPort.findById(null)).isInstanceOf(NullPointerException.class);
  }
}
