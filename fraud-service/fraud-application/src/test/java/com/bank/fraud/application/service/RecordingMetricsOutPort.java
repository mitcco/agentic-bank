package com.bank.fraud.application.service;

import com.bank.fraud.application.port.out.MetricsOutPort;
import com.bank.fraud.domain.FraudReasonEnum;
import com.bank.fraud.domain.FraudStatusEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake della porta delle metriche: registra le chiamate ricevute, nell'ordine.
 */
class RecordingMetricsOutPort implements MetricsOutPort {

    record Call(FraudStatusEnum status, List<FraudReasonEnum> reasons) {
    }

    private final List<Call> calls = new ArrayList<>();

    List<Call> calls() {
        return calls;
    }

    @Override
    public void assessmentCompleted(FraudStatusEnum status, List<FraudReasonEnum> reasons) {
        calls.add(new Call(status, List.copyOf(reasons)));
    }
}
