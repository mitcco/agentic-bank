package com.bank.transfer.postgres.repository;

import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.postgres.repository.model.ExternalTransferEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataExternalTransferRepository extends JpaRepository<ExternalTransferEntity, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from ExternalTransferEntity t where t.id = :id")
  Optional<ExternalTransferEntity> findByIdForUpdate(@Param("id") String id);

  List<ExternalTransferEntity> findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
    Collection<TransferStatusEnum> statuses, LocalDateTime threshold, Limit limit);
}
