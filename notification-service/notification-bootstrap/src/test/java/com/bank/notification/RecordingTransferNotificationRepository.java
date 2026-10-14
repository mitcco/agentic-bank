package com.bank.notification;

import com.bank.notification.mongo.repository.TransferNotificationRepository;
import com.bank.notification.mongo.repository.model.TransferNotificationDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fake of the repository: records the saved documents, can be told to throw and can
 * be cleared between tests.
 */
class RecordingTransferNotificationRepository implements TransferNotificationRepository {

  private final List<TransferNotificationDocument> saved = new ArrayList<>();
  private RuntimeException toThrow;

  @Override
  public synchronized TransferNotificationDocument save(TransferNotificationDocument document) {
    saved.add(document);
    if (toThrow != null) {
      throw toThrow;
    }
    return document;
  }

  synchronized List<TransferNotificationDocument> saved() {
    return List.copyOf(saved);
  }

  synchronized void failWith(RuntimeException failure) {
    this.toThrow = failure;
  }

  synchronized void clear() {
    saved.clear();
    toThrow = null;
  }
}
