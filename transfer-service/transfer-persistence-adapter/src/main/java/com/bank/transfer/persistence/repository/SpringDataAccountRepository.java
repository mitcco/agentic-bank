package com.bank.transfer.persistence.repository;

import com.bank.transfer.persistence.repository.model.AccountEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAccountRepository extends JpaRepository<AccountEntity, String> {

  Optional<AccountEntity> findByIban(String iban);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select account from AccountEntity account where account.iban = :iban")
  Optional<AccountEntity> findByIbanForUpdate(@Param("iban") String iban);
}
