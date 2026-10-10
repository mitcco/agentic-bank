package com.bank.transfer.adapter.out.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTransferRepository extends JpaRepository<TransferEntity, String> {

}
