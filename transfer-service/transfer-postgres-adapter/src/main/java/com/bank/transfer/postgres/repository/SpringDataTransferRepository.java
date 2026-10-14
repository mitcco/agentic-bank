package com.bank.transfer.postgres.repository;

import com.bank.transfer.postgres.repository.model.TransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataTransferRepository extends JpaRepository<TransferEntity, String> {

}
