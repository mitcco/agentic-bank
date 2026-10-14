package com.bank.transfer.application.port.in;

import com.bank.transfer.application.dto.PublishResultDto;

public interface PublishPendingNotificationsInPort {

  PublishResultDto publishPending(int maxRows);
}
