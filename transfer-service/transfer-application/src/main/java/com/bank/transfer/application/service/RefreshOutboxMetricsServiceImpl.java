package com.bank.transfer.application.service;

import com.bank.transfer.application.port.in.RefreshOutboxMetricsInPort;
import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.application.port.out.PendingNotificationCountOutPort;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class RefreshOutboxMetricsServiceImpl implements RefreshOutboxMetricsInPort {

  private final PendingNotificationCountOutPort pendingNotificationCountOutPort;
  private final MetricsOutPort metricsOutPort;

  public RefreshOutboxMetricsServiceImpl(PendingNotificationCountOutPort pendingNotificationCountOutPort,
    MetricsOutPort metricsOutPort) {
    this.pendingNotificationCountOutPort =
      Objects.requireNonNull(pendingNotificationCountOutPort, "pendingNotificationCountOutPort");
    this.metricsOutPort = Objects.requireNonNull(metricsOutPort, "metricsOutPort");
  }

  @Override
  public void refreshPendingNotifications() {
    metricsOutPort.outboxPending(pendingNotificationCountOutPort.countPending());
  }
}
