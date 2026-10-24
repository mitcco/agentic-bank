package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.domain.TransferStatusEnum;

/**
 * Business metrics of the transfers and of the outbox. Only enums and numbers: never IBANs, amounts or ids.
 */
public interface MetricsOutPort {

  void transferRequested(TransferTypeEnum type, TransferStatusEnum status);

  void transferConcluded(TransferTypeEnum type, TransferStatusEnum status);

  void transferRefunded(RefundCauseEnum cause);

  void reconciliationExamined(ReconciliationOutcomeEnum outcome);

  void notificationPublished();

  void outboxPending(long rows);
}
