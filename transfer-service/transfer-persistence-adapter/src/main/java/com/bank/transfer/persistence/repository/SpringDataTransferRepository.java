package com.bank.transfer.persistence.repository;

import com.bank.transfer.persistence.repository.model.TransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataTransferRepository extends JpaRepository<TransferEntity, String> {

}
