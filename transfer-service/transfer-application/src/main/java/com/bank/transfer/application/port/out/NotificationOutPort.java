package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.TransferNotificationDto;

public interface NotificationOutPort {

  void publish(TransferNotificationDto notification);
}
