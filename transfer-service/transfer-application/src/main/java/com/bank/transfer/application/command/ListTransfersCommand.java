package com.bank.transfer.application.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raw, unvalidated parameters of the list of transfers of an IBAN. Every field is optional here: the service
 * validates them. A null list of statuses becomes an empty one; otherwise it is copied into an immutable list
 * (null elements are tolerated, so the service can reject them with its own validation error).
 */
public record ListTransfersCommand(String iban, String direction, List<String> statuses, String type, String from,
                                   String to, String page, String size) {

  public ListTransfersCommand {
    statuses = statuses == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(statuses));
  }

  /** No field is printed: IBANs must never be printed, and the other raw values are not needed either. */
  @Override
  public String toString() {
    return "ListTransfersCommand[fields omitted]";
  }
}
