package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.StoredTransferListDto;
import com.bank.transfer.application.dto.TransferListCriteria;

public interface TransferListQueryOutPort {

  /**
   * Reads the internal and external transfers matching the criteria, without locking: the rows from offset to
   * offset + size, newest first and then by id descending, and the exact total of the matching rows.
   */
  StoredTransferListDto findByIban(TransferListCriteria criteria);
}
