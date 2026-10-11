package com.bank.fraud.application.port.out;

import com.bank.fraud.domain.Iban;

import java.util.Set;

public interface BlockedIbanOutPort {

    /**
     * Returns the subset of the given IBANs that are blocked.
     * The result is never {@code null}; it is empty when none is blocked.
     */
    Set<Iban> findBlocked(Set<Iban> ibans);
}
