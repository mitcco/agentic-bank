package com.bank.transfer.postgres.repository;

import com.bank.transfer.postgres.repository.model.OutboxEntity;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEntity, Long> {

  List<OutboxEntity> findAllByOrderByIdAsc(Limit limit);
}
