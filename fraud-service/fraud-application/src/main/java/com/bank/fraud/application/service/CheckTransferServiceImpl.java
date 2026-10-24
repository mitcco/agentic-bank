package com.bank.fraud.application.service;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.application.exception.InvalidFraudCheckRequestException;
import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.application.port.out.MetricsOutPort;
import com.bank.fraud.domain.FraudAssessment;
import com.bank.fraud.domain.FraudPolicy;
import com.bank.fraud.domain.Iban;
import com.bank.fraud.domain.Money;
import com.bank.fraud.domain.TransferToAssess;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class CheckTransferServiceImpl implements CheckTransferInPort {

    private final BlockedIbanOutPort blockedIbanOutPort;
    private final FraudPolicy policy;

    private final MetricsOutPort metricsOutPort;

    public CheckTransferServiceImpl(BlockedIbanOutPort blockedIbanOutPort, BigDecimal threshold,
                                    MetricsOutPort metricsOutPort) {
        this.blockedIbanOutPort = Objects.requireNonNull(blockedIbanOutPort, "blockedIbanOutPort");
        this.policy = new FraudPolicy(new Money(threshold, TransferToAssess.SUPPORTED_CURRENCY));
        this.metricsOutPort = Objects.requireNonNull(metricsOutPort, "metricsOutPort");
    }

    @Override
    public FraudCheckResponseDto checkTransfer(FraudCheckRequestDto request) {
        TransferToAssess transfer;
        try {
            transfer = new TransferToAssess(
                    request.transferId(),
                    new Iban(request.sourceIban()),
                    new Iban(request.destinationIban()),
                    new Money(request.amount(), request.currency()));
        } catch (InvalidFraudRequestException e) {
            throw new InvalidFraudCheckRequestException(e);
        }

        Set<Iban> toCheck = new LinkedHashSet<>(List.of(transfer.source(), transfer.destination()));
        Set<Iban> blocked = Objects.requireNonNull(blockedIbanOutPort.findBlocked(toCheck), "blocked IBANs");

        FraudAssessment assessment = policy.assess(transfer, blocked::contains);
        metricsOutPort.assessmentCompleted(assessment.status(), assessment.reasons());

        return new FraudCheckResponseDto(
                assessment.transferId(),
                assessment.status().name(),
                assessment.reasons().stream()
                        .map(reason -> reason.message(policy.threshold()))
                        .toList());
    }
}
