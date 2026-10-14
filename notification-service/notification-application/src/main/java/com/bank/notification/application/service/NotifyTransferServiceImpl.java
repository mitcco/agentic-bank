package com.bank.notification.application.service;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.application.port.in.NotifyTransferInPort;
import com.bank.notification.application.port.out.NotificationLogOutPort;
import com.bank.notification.application.port.out.NotificationStoreOutPort;
import com.bank.notification.domain.Iban;
import com.bank.notification.domain.Money;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import com.bank.notification.domain.exception.InvalidNotificationException;

import java.util.Objects;

/**
 * Saves the notification first and writes the log line after. The bean is declared by the
 * bootstrap ({@code NotificationConfig}, phase 07).
 */
public class NotifyTransferServiceImpl implements NotifyTransferInPort {

  private final NotificationStoreOutPort store;
  private final NotificationLogOutPort log;

  public NotifyTransferServiceImpl(NotificationStoreOutPort store, NotificationLogOutPort log) {
    this.store = Objects.requireNonNull(store, "store");
    this.log = Objects.requireNonNull(log, "log");
  }

  @Override
  public void notifyTransfer(NotifyTransferCommand command) {
    Objects.requireNonNull(command, "command");
    TransferNotification notification = new TransferNotification(
        command.transferId(),
        new Iban(command.sourceIban()),
        new Iban(command.destinationIban()),
        new Money(command.amount(), command.currency()),
        toOutcome(command.status()),
        command.reasons(),
        command.occurredAt());
    store.save(notification);
    log.log(notification);
  }

  private static TransferOutcome toOutcome(String status) {
    if (status == null) {
      return null;
    }
    if ("COMPLETED".equals(status)) {
      return TransferOutcome.COMPLETED;
    }
    if ("REJECTED".equals(status)) {
      return TransferOutcome.REJECTED;
    }
    throw new InvalidNotificationException("status is not supported");
  }
}
