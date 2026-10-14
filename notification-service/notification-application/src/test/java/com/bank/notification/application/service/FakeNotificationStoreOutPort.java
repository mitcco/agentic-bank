package com.bank.notification.application.service;

import com.bank.notification.application.port.out.NotificationStoreOutPort;
import com.bank.notification.domain.TransferNotification;

import java.util.ArrayList;
import java.util.List;

class FakeNotificationStoreOutPort implements NotificationStoreOutPort {

  private final List<String> registry;
  private final List<TransferNotification> saved = new ArrayList<>();
  private RuntimeException toThrow;

  FakeNotificationStoreOutPort(List<String> registry) {
    this.registry = registry;
  }

  FakeNotificationStoreOutPort throwing(RuntimeException e) {
    this.toThrow = e;
    return this;
  }

  List<TransferNotification> saved() {
    return saved;
  }

  @Override
  public void save(TransferNotification notification) {
    registry.add("save");
    saved.add(notification);
    if (toThrow != null) {
      throw toThrow;
    }
  }
}
