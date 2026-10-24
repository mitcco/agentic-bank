package com.bank.transfer.application.service;

import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake della porta delle metriche: registra le chiamate ricevute, nell'ordine, come testo
 * (per esempio {@code requested(EXTERNAL,SENT)}). I metodi statici costruiscono lo stesso testo,
 * cosi i test confrontano chiamate senza ripetere le stringhe.
 */
class RecordingMetricsOutPort implements MetricsOutPort {

  private final List<String> calls = new ArrayList<>();

  List<String> calls() {
    return calls;
  }

  static String requested(TransferTypeEnum type, TransferStatusEnum status) {
    return "requested(" + type + "," + status + ")";
  }

  static String concluded(TransferTypeEnum type, TransferStatusEnum status) {
    return "concluded(" + type + "," + status + ")";
  }

  static String refunded(RefundCauseEnum cause) {
    return "refunded(" + cause + ")";
  }

  static String reconciliation(ReconciliationOutcomeEnum outcome) {
    return "reconciliation(" + outcome + ")";
  }

  static String published() {
    return "published";
  }

  static String pending(long rows) {
    return "pending(" + rows + ")";
  }

  @Override
  public void transferRequested(TransferTypeEnum type, TransferStatusEnum status) {
    calls.add(requested(type, status));
  }

  @Override
  public void transferConcluded(TransferTypeEnum type, TransferStatusEnum status) {
    calls.add(concluded(type, status));
  }

  @Override
  public void transferRefunded(RefundCauseEnum cause) {
    calls.add(refunded(cause));
  }

  @Override
  public void reconciliationExamined(ReconciliationOutcomeEnum outcome) {
    calls.add(reconciliation(outcome));
  }

  @Override
  public void notificationPublished() {
    calls.add(published());
  }

  @Override
  public void outboxPending(long rows) {
    calls.add(pending(rows));
  }
}
