package com.bank.transfer.application.port.in;

public interface ProcessClearingOutcomesInPort {

  /**
   * Applies the outcomes that the clearing house has ready.
   *
   * @return the number of outcomes applied
   */
  int processOutcomes();
}
