package com.bank.notification.kafka.port.in.kafka;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.application.port.in.NotifyTransferInPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Hand-written fake of the use case. It records every command, runs an optional hook (which
 * may block or throw) and then throws the configured failure, if any. Safe to call from the
 * thread of a Kafka listener container.
 */
class FakeNotifyTransferInPort implements NotifyTransferInPort {

  private final List<NotifyTransferCommand> calls = new CopyOnWriteArrayList<>();
  private volatile RuntimeException toThrow;
  private volatile Error errorToThrow;
  private volatile Consumer<NotifyTransferCommand> hook;

  FakeNotifyTransferInPort throwing(RuntimeException e) {
    this.toThrow = e;
    return this;
  }

  FakeNotifyTransferInPort throwingError(Error e) {
    this.errorToThrow = e;
    return this;
  }

  FakeNotifyTransferInPort onCall(Consumer<NotifyTransferCommand> hook) {
    this.hook = hook;
    return this;
  }

  List<NotifyTransferCommand> calls() {
    return calls;
  }

  @Override
  public void notifyTransfer(NotifyTransferCommand command) {
    calls.add(command);
    Consumer<NotifyTransferCommand> currentHook = hook;
    if (currentHook != null) {
      currentHook.accept(command);
    }
    if (toThrow != null) {
      throw toThrow;
    }
    if (errorToThrow != null) {
      throw errorToThrow;
    }
  }
}
