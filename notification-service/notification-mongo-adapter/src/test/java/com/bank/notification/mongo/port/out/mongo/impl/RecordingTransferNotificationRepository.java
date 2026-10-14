package com.bank.notification.mongo.port.out.mongo.impl;

import com.bank.notification.mongo.repository.TransferNotificationRepository;
import com.bank.notification.mongo.repository.model.TransferNotificationDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fake of the repository: records the saved documents and can be told to throw.
 */
class RecordingTransferNotificationRepository implements TransferNotificationRepository {

  private final List<TransferNotificationDocument> saved = new ArrayList<>();
  private RuntimeException toThrow;

  @Override
  public TransferNotificationDocument save(TransferNotificationDocument document) {
    saved.add(document);
    if (toThrow != null) {
      throw toThrow;
    }
    return document;
  }

  List<TransferNotificationDocument> saved() {
    return List.copyOf(saved);
  }

  void failWith(RuntimeException failure) {
    this.toThrow = failure;
  }
}
