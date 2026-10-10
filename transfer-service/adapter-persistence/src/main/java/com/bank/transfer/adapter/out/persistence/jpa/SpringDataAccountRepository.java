package com.bank.transfer.adapter.out.persistence.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataAccountRepository extends JpaRepository<AccountEntity, String> {

  Optional<AccountEntity> findByIban(String iban);

  /**
   * SELECT ... FOR UPDATE: it needs an active transaction, otherwise JPA throws TransactionRequiredException.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AccountEntity a where a.iban = :iban")
  Optional<AccountEntity> findByIbanForUpdate(@Param("iban") String iban);
}
