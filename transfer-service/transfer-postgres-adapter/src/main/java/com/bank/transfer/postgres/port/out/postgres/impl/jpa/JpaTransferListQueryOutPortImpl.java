package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.dto.StoredTransferListDto;
import com.bank.transfer.application.dto.StoredTransferRowDto;
import com.bank.transfer.application.dto.TransferDirectionEnum;
import com.bank.transfer.application.dto.TransferListCriteria;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.port.out.TransferListQueryOutPort;
import com.bank.transfer.postgres.repository.SpringDataExternalTransferRepository;
import com.bank.transfer.postgres.repository.SpringDataTransferRepository;
import com.bank.transfer.postgres.repository.model.ExternalTransferEntity;
import com.bank.transfer.postgres.repository.model.TransferEntity;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTransferListQueryOutPortImpl implements TransferListQueryOutPort {

  private static final Comparator<StoredTransferRowDto> NEWEST_FIRST = Comparator
    .comparing(StoredTransferRowDto::createdAt, Comparator.reverseOrder())
    .thenComparing(StoredTransferRowDto::transferId, Comparator.reverseOrder());

  private final SpringDataTransferRepository transferRepository;
  private final SpringDataExternalTransferRepository externalTransferRepository;

  JpaTransferListQueryOutPortImpl(SpringDataTransferRepository transferRepository,
    SpringDataExternalTransferRepository externalTransferRepository) {
    this.transferRepository = Objects.requireNonNull(transferRepository, "transferRepository");
    this.externalTransferRepository = Objects.requireNonNull(externalTransferRepository,
      "externalTransferRepository");
  }

  /**
   * Reads the first offset + size rows of each requested table, merges them newest first and cuts the slice. The
   * internal table stores created_at as local time of the JVM, the external one as UTC: both become instants. The
   * total is the sum of the two counts. No lock.
   */
  @Override
  public StoredTransferListDto findByIban(TransferListCriteria criteria) {
    Objects.requireNonNull(criteria, "criteria");
    PageRequest window = PageRequest.of(0, criteria.offset() + criteria.size(),
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    List<StoredTransferRowDto> merged = new ArrayList<>();
    long total = 0;
    if (criteria.types().contains(TransferTypeEnum.INTERNAL)) {
      Page<TransferEntity> page = transferRepository.findAll(specificationOf(criteria, ZoneId.systemDefault()),
        window);
      page.getContent().forEach(entity -> merged.add(toRow(entity)));
      total += page.getTotalElements();
    }
    if (criteria.types().contains(TransferTypeEnum.EXTERNAL)) {
      Page<ExternalTransferEntity> page = externalTransferRepository.findAll(
        specificationOf(criteria, ZoneOffset.UTC), window);
      page.getContent().forEach(entity -> merged.add(toRow(entity)));
      total += page.getTotalElements();
    }
    merged.sort(NEWEST_FIRST);
    int from = Math.min(criteria.offset(), merged.size());
    int to = Math.min(criteria.offset() + criteria.size(), merged.size());
    return new StoredTransferListDto(List.copyOf(merged.subList(from, to)), total);
  }

  /** Both entities have the same attribute names, so one specification serves both tables. */
  private static <T> Specification<T> specificationOf(TransferListCriteria criteria, ZoneId zone) {
    return (root, query, builder) -> {
      List<Predicate> predicates = new ArrayList<>();
      String iban = criteria.iban().value();
      if (criteria.direction() == TransferDirectionEnum.OUTGOING) {
        predicates.add(builder.equal(root.get("sourceIban"), iban));
      } else if (criteria.direction() == TransferDirectionEnum.INCOMING) {
        predicates.add(builder.equal(root.get("destIban"), iban));
      } else {
        predicates.add(builder.or(builder.equal(root.get("sourceIban"), iban),
          builder.equal(root.get("destIban"), iban)));
      }
      if (!criteria.statuses().isEmpty()) {
        predicates.add(root.get("status").in(criteria.statuses()));
      }
      if (criteria.from() != null) {
        predicates.add(builder.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"),
          LocalDateTime.ofInstant(criteria.from(), zone)));
      }
      if (criteria.to() != null) {
        predicates.add(builder.lessThan(root.<LocalDateTime>get("createdAt"),
          LocalDateTime.ofInstant(criteria.to(), zone)));
      }
      return builder.and(predicates.toArray(new Predicate[0]));
    };
  }

  private static StoredTransferRowDto toRow(TransferEntity entity) {
    Instant createdAt = entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
    return new StoredTransferRowDto(entity.getId(), TransferTypeEnum.INTERNAL, entity.getSourceIban(),
      entity.getDestIban(), entity.getAmount(), entity.getCurrency(), entity.getStatus(), createdAt);
  }

  private static StoredTransferRowDto toRow(ExternalTransferEntity entity) {
    return new StoredTransferRowDto(entity.getId(), TransferTypeEnum.EXTERNAL, entity.getSourceIban(),
      entity.getDestIban(), entity.getAmount(), entity.getCurrency(), entity.getStatus(),
      entity.getCreatedAt().toInstant(ZoneOffset.UTC));
  }
}
