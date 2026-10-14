package com.bank.notification.mongo.repository;

import com.bank.notification.mongo.repository.model.TransferNotificationDocument;
import org.springframework.data.repository.Repository;

/**
 * Spring Data repository of the notification documents. Exposes only {@code save}.
 */
public interface TransferNotificationRepository
    extends Repository<TransferNotificationDocument, String> {

  TransferNotificationDocument save(TransferNotificationDocument document);
}
