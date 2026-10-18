package com.bank.notification.mongo.port.out.mongo.impl;

import static java.util.Objects.requireNonNull;

import com.bank.notification.application.port.out.NotificationStoreOutPort;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.mongo.repository.TransferNotificationRepository;

/**
 * Stores the notifications in MongoDB. Exceptions of the repository are not caught.
 */
public class NotificationStoreOutPortImpl implements NotificationStoreOutPort {

  private final TransferNotificationRepository repository;
  private final TransferNotificationDocumentMapper mapper = new TransferNotificationDocumentMapper();

  public NotificationStoreOutPortImpl(TransferNotificationRepository repository) {
    this.repository = requireNonNull(repository);
  }

  @Override
  public void save(TransferNotification notification) {
    requireNonNull(notification);
    repository.save(mapper.toDocument(notification));
  }
}
