package com.bank.transfer.application.port.out;

import com.bank.transfer.domain.Transfer;

public interface TransferRepositoryOutPort {
  Transfer save(Transfer transfer);
}
