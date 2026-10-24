package com.bank.fraud.micrometer.port.out.micrometer.impl;

import com.bank.fraud.application.port.out.MetricsOutPort;
import com.bank.fraud.domain.FraudReasonEnum;
import com.bank.fraud.domain.FraudStatusEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MicrometerMetricsOutPortImpl implements MetricsOutPort {

    private static final String ASSESSMENTS = "bank.fraud.assessments";
    private static final String REASONS = "bank.fraud.reasons";

    private final Map<FraudStatusEnum, Counter> assessments = new EnumMap<>(FraudStatusEnum.class);
    private final Map<FraudReasonEnum, Counter> reasons = new EnumMap<>(FraudReasonEnum.class);

    public MicrometerMetricsOutPortImpl(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (FraudStatusEnum status : FraudStatusEnum.values()) {
            assessments.put(status, Counter.builder(ASSESSMENTS)
                    .description("Fraud assessments completed, by outcome")
                    .tag("status", status.name())
                    .register(registry));
        }
        for (FraudReasonEnum reason : FraudReasonEnum.values()) {
            reasons.put(reason, Counter.builder(REASONS)
                    .description("Fraud rejection reasons, by reason")
                    .tag("reason", reason.name())
                    .register(registry));
        }
    }

    @Override
    public void assessmentCompleted(FraudStatusEnum status, List<FraudReasonEnum> reasonList) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonList, "reasons");
        reasonList.forEach(reason -> Objects.requireNonNull(reason, "reason"));
        assessments.get(status).increment();
        reasonList.forEach(reason -> reasons.get(reason).increment());
    }
}
