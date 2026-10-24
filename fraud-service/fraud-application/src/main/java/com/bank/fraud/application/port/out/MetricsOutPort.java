package com.bank.fraud.application.port.out;

import com.bank.fraud.domain.FraudReasonEnum;
import com.bank.fraud.domain.FraudStatusEnum;

import java.util.List;

public interface MetricsOutPort {

    void assessmentCompleted(FraudStatusEnum status, List<FraudReasonEnum> reasons);
}
