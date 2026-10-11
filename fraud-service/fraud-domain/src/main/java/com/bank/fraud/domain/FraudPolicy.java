package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class FraudPolicy {

    private final Money threshold;

    public FraudPolicy(Money threshold) {
        if (threshold == null) {
            throw new InvalidFraudRequestException("Threshold must not be null");
        }
        if (!TransferToAssess.SUPPORTED_CURRENCY.equals(threshold.currency())) {
            throw new InvalidFraudRequestException("Threshold currency must be EUR");
        }
        if (threshold.amount().signum() <= 0) {
            throw new InvalidFraudRequestException("Threshold must be greater than 0");
        }
        this.threshold = threshold;
    }

    public Money threshold() {
        return threshold;
    }

    public FraudAssessment assess(TransferToAssess transfer, Predicate<Iban> isBlocked) {
        if (transfer == null) {
            throw new InvalidFraudRequestException("Transfer must not be null");
        }
        if (isBlocked == null) {
            throw new InvalidFraudRequestException("Blocked IBAN predicate must not be null");
        }
        List<FraudReasonEnum> reasons = new ArrayList<>();
        if (transfer.amount().isGreaterThan(threshold)) {
            reasons.add(FraudReasonEnum.AMOUNT_OVER_THRESHOLD);
        }
        if (isBlocked.test(transfer.source())) {
            reasons.add(FraudReasonEnum.SOURCE_IBAN_LOCKED);
        }
        if (isBlocked.test(transfer.destination())) {
            reasons.add(FraudReasonEnum.DESTINATION_IBAN_LOCKED);
        }
        return reasons.isEmpty()
                ? FraudAssessment.allowed(transfer.transferId())
                : FraudAssessment.rejected(transfer.transferId(), reasons);
    }
}
