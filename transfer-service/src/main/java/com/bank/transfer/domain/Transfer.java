package com.bank.transfer.domain;

public class Transfer {
  private String id;
  private Iban source;
  private Iban dest;
  private Money money;
  private TransferStatusEnum status;

  public Transfer(String id, Iban source, Iban dest, Money money, TransferStatusEnum status) {
    if(money.amount().signum() < 0) {
      throw new IllegalArgumentException("Transfer amount has to be greater than 0");
    }
    if(source.value().equals(dest.value())) {
      throw new IllegalArgumentException("Source and destination IBANs can not be the same");
    }
    this.id = id;
    this.source = source;
    this.dest = dest;
    this.money = money;
    this.status = status;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Iban getSource() {
    return source;
  }

  public void setSource(Iban source) {
    this.source = source;
  }

  public Iban getDest() {
    return dest;
  }

  public void setDest(Iban dest) {
    this.dest = dest;
  }

  public Money getMoney() {
    return money;
  }

  public void setMoney(Money money) {
    this.money = money;
  }

  public TransferStatusEnum getStatus() {
    return status;
  }

  public void setStatus(TransferStatusEnum status) {
    this.status = status;
  }
}
