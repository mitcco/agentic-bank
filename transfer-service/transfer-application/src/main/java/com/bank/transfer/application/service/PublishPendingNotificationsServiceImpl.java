package com.bank.transfer.application.service;

import com.bank.transfer.application.dto.PendingNotificationDto;
import com.bank.transfer.application.dto.PublishFailureStageEnum;
import com.bank.transfer.application.dto.PublishResultDto;
import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import com.bank.transfer.application.port.out.PendingNotificationOutPort;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PublishPendingNotificationsServiceImpl implements PublishPendingNotificationsInPort {

  private final PendingNotificationOutPort pendingNotificationOutPort;
  private final NotificationSenderOutPort notificationSenderOutPort;
  private final MetricsOutPort metricsOutPort;

  public PublishPendingNotificationsServiceImpl(PendingNotificationOutPort pendingNotificationOutPort,
    NotificationSenderOutPort notificationSenderOutPort, MetricsOutPort metricsOutPort) {
    this.pendingNotificationOutPort = pendingNotificationOutPort;
    this.notificationSenderOutPort = notificationSenderOutPort;
    this.metricsOutPort = Objects.requireNonNull(metricsOutPort, "metricsOutPort");
  }

  @Override
  public PublishResultDto publishPending(int maxRows) {
    if (maxRows < 1) {
      throw new IllegalArgumentException("maxRows must be at least 1");
    }
    List<PendingNotificationDto> rows = pendingNotificationOutPort.findPending(maxRows);
    int sent = 0;
    for (PendingNotificationDto row : rows) {
      try {
        notificationSenderOutPort.send(row.transferId(), row.payload());
      } catch (RuntimeException e) {
        return new PublishResultDto(rows.size(), sent, row.transferId(), PublishFailureStageEnum.SEND);
      }
      sent++;
      metricsOutPort.notificationPublished();
      try {
        pendingNotificationOutPort.delete(row.id());
      } catch (RuntimeException e) {
        return new PublishResultDto(rows.size(), sent, row.transferId(), PublishFailureStageEnum.DELETE);
      }
    }
    return new PublishResultDto(rows.size(), sent, null, null);
  }
}
