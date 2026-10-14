package com.bank.notification.application.port.in;

import com.bank.notification.application.command.NotifyTransferCommand;

/**
 * Use case: record the outcome of a transfer.
 */
public interface NotifyTransferInPort {

  /**
   * Saves the notification and then writes the log line.
   *
   * <p>Exceptions thrown by the outbound ports are propagated unchanged.
   *
   * @param command the transfer outcome, as received
   * @throws com.bank.notification.domain.exception.InvalidNotificationException if the command
   *     violates the rules of the contract (no port is called)
   */
  void notifyTransfer(NotifyTransferCommand command);
}
