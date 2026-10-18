package com.bank.notification.kafka.port.in.kafka;

import com.bank.notification.application.port.out.NotificationLogOutPort;
import com.bank.notification.domain.TransferNotification;

import java.util.ArrayList;
import java.util.List;

class FakeNotificationLogOutPort implements NotificationLogOutPort {

  private final List<String> registry;
  private final List<TransferNotification> logged = new ArrayList<>();
  private RuntimeException toThrow;

  FakeNotificationLogOutPort(List<String> registry) {
    this.registry = registry;
  }

  FakeNotificationLogOutPort throwing(RuntimeException e) {
    this.toThrow = e;
    return this;
  }

  List<TransferNotification> logged() {
    return logged;
  }

  @Override
  public void log(TransferNotification notification) {
    registry.add("log");
    logged.add(notification);
    if (toThrow != null) {
      throw toThrow;
    }
  }
}
