package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.StoredTransferListDto;
import com.bank.transfer.application.dto.StoredTransferRowDto;
import com.bank.transfer.application.dto.TransferDirectionEnum;
import com.bank.transfer.application.dto.TransferListCriteria;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.postgres.repository.SpringDataTransferRepository;
import com.bank.transfer.postgres.repository.model.TransferEntity;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * transfer.created_at is written by the database in the JVM time zone, external_transfer.created_at by the
 * application in UTC: the tests set the dates through JDBC (internal ones as local time of the JVM, external ones as
 * UTC) and expect the same Instants back.
 */
@DataJpaTest
@Import({
  JpaTransferListQueryOutPortImpl.class,
  JpaAccountRepositoryOutPortImpl.class,
  JpaTransferRepositoryOutPortImpl.class,
  JpaExternalTransferRepositoryOutPortImpl.class
})
class JpaTransferListQueryOutPortImplTest {

  private static final Iban X = new Iban("IT60X0542811101000000888001");
  private static final Iban Y = new Iban("IT60X0542811101000000888002");
  private static final Iban EXTERNAL = new Iban("IT60X0306909606100000012345");
  private static final Iban UNKNOWN = new Iban("IT60X0542811101000000888999");
  /** Mid-summer: far from any daylight saving transition. */
  private static final Instant T0 = Instant.parse("2026-07-15T08:00:00Z");

  @Autowired
  private JpaTransferListQueryOutPortImpl listPort;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accountRepository;

  @Autowired
  private JpaTransferRepositoryOutPortImpl transferRepository;

  @Autowired
  private JpaExternalTransferRepositoryOutPortImpl externalTransferRepository;

  @Autowired
  private SpringDataTransferRepository springDataTransferRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  /** transfer.source_iban and dest_iban reference account.iban; external_transfer only the source. */
  @BeforeEach
  void createAccounts() {
    accountRepository.save(new Account("l-acc-x", X, new Money(new BigDecimal("1000"), "EUR")));
    accountRepository.save(new Account("l-acc-y", Y, new Money(new BigDecimal("1000"), "EUR")));
    flushAndClear();
  }

  // ------------------------------------------------------------------ helpers

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private static LocalDateTime localTimeOf(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
  }

  private static LocalDateTime utcTimeOf(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant at(int hours) {
    return T0.plus(Duration.ofHours(hours));
  }

  /** Internal transfer whose created_at is the given instant, written as local time of the JVM. */
  private void internal(String id, Iban source, Iban dest, String amount, TransferStatusEnum status, Instant when) {
    transferRepository.save(new Transfer(id, source, dest, new Money(new BigDecimal(amount), "EUR"), status));
    entityManager.flush();
    jdbcTemplate.update("update transfer set created_at = ? where id = ?",
      Timestamp.valueOf(localTimeOf(when)), id);
    entityManager.clear();
  }

  /** External transfer whose created_at is the given instant, written as UTC. */
  private void external(String id, Iban source, Iban dest, String amount, TransferStatusEnum status, Instant when) {
    externalTransferRepository.save(new Transfer(id, source, dest, new Money(new BigDecimal(amount), "EUR"), status),
      null);
    entityManager.flush();
    jdbcTemplate.update("update external_transfer set created_at = ? where id = ?",
      Timestamp.valueOf(utcTimeOf(when)), id);
    entityManager.clear();
  }

  private static TransferListCriteria criteria(Iban iban, TransferDirectionEnum direction,
    Set<TransferStatusEnum> statuses, Set<TransferTypeEnum> types, Instant from, Instant to, int offset, int size) {
    return new TransferListCriteria(iban, direction, statuses, types, from, to, offset, size);
  }

  private static TransferListCriteria all(Iban iban) {
    return criteria(iban, TransferDirectionEnum.ALL, Set.of(), EnumSet.allOf(TransferTypeEnum.class), null, null, 0,
      100);
  }

  private static TransferListCriteria withDirection(Iban iban, TransferDirectionEnum direction) {
    return criteria(iban, direction, Set.of(), EnumSet.allOf(TransferTypeEnum.class), null, null, 0, 100);
  }

  private static TransferListCriteria withStatusesAndTypes(Set<TransferStatusEnum> statuses,
    Set<TransferTypeEnum> types) {
    return criteria(X, TransferDirectionEnum.ALL, statuses, types, null, null, 0, 100);
  }

  private static TransferListCriteria withDates(Instant from, Instant to) {
    return criteria(X, TransferDirectionEnum.ALL, Set.of(), EnumSet.allOf(TransferTypeEnum.class), from, to, 0, 100);
  }

  private static TransferListCriteria withPaging(int offset, int size) {
    return criteria(X, TransferDirectionEnum.ALL, Set.of(), EnumSet.allOf(TransferTypeEnum.class), null, null,
      offset, size);
  }

  private static List<String> ids(StoredTransferListDto result) {
    return result.rows().stream().map(StoredTransferRowDto::transferId).toList();
  }

  /** Three transfers of X: an internal and an external one sent by X, an internal one received by X. */
  private void threeTransfersOfX() {
    internal("l-int-out", X, Y, "10.50", TransferStatusEnum.COMPLETED, at(0));
    internal("l-int-in", Y, X, "20.00", TransferStatusEnum.COMPLETED, at(1));
    external("l-ext-out", X, EXTERNAL, "25.75", TransferStatusEnum.COMPLETED, at(2));
  }

  // ------------------------------------------------------------------ directions

  @Test
  void allDirectionsBothTablesNewestFirst() {
    threeTransfersOfX();

    StoredTransferListDto result = listPort.findByIban(all(X));

    assertThat(ids(result)).containsExactly("l-ext-out", "l-int-in", "l-int-out");
    assertThat(result.total()).isEqualTo(3L);
  }

  @Test
  void rowsCarryTheirFieldsAndTheDateAsTheSameInstantThatWasStored() {
    threeTransfersOfX();

    List<StoredTransferRowDto> rows = listPort.findByIban(all(X)).rows();

    StoredTransferRowDto external = rows.get(0);
    assertThat(external.transferId()).isEqualTo("l-ext-out");
    assertThat(external.type()).isEqualTo(TransferTypeEnum.EXTERNAL);
    assertThat(external.sourceIban()).isEqualTo(X.value());
    assertThat(external.destIban()).isEqualTo(EXTERNAL.value());
    assertThat(external.amount()).isEqualByComparingTo("25.75");
    assertThat(external.currency()).isEqualTo("EUR");
    assertThat(external.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(external.createdAt()).isEqualTo(at(2));

    StoredTransferRowDto incoming = rows.get(1);
    assertThat(incoming.transferId()).isEqualTo("l-int-in");
    assertThat(incoming.type()).isEqualTo(TransferTypeEnum.INTERNAL);
    assertThat(incoming.sourceIban()).isEqualTo(Y.value());
    assertThat(incoming.destIban()).isEqualTo(X.value());
    assertThat(incoming.amount()).isEqualByComparingTo("20.00");
    assertThat(incoming.createdAt()).isEqualTo(at(1));

    assertThat(rows.get(2).createdAt()).isEqualTo(at(0));
  }

  @Test
  void outgoingAndIncoming() {
    threeTransfersOfX();

    StoredTransferListDto outgoing = listPort.findByIban(withDirection(X, TransferDirectionEnum.OUTGOING));
    StoredTransferListDto incoming = listPort.findByIban(withDirection(X, TransferDirectionEnum.INCOMING));

    assertThat(ids(outgoing)).containsExactly("l-ext-out", "l-int-out");
    assertThat(outgoing.total()).isEqualTo(2L);
    assertThat(ids(incoming)).containsExactly("l-int-in");
    assertThat(incoming.total()).isEqualTo(1L);
  }

  @Test
  void anExternalIbanSeesTheExternalTransfersItReceived() {
    threeTransfersOfX();

    StoredTransferListDto all = listPort.findByIban(all(EXTERNAL));
    StoredTransferListDto outgoing = listPort.findByIban(withDirection(EXTERNAL, TransferDirectionEnum.OUTGOING));

    assertThat(ids(all)).containsExactly("l-ext-out");
    assertThat(all.total()).isEqualTo(1L);
    assertThat(outgoing.rows()).isEmpty();
    assertThat(outgoing.total()).isZero();
  }

  // ------------------------------------------------------------------ status and type filters

  private void mixedStatuses() {
    internal("l-int-completed", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));
    internal("l-int-rejected", Y, X, "2.00", TransferStatusEnum.REJECTED, at(1));
    external("l-ext-sent", X, EXTERNAL, "3.00", TransferStatusEnum.SENT, at(2));
    external("l-ext-rejected", X, EXTERNAL, "4.00", TransferStatusEnum.REJECTED, at(3));
    external("l-ext-completed", X, EXTERNAL, "5.00", TransferStatusEnum.COMPLETED, at(4));
  }

  @Test
  void noStatusFilterMeansAllStatuses() {
    mixedStatuses();

    StoredTransferListDto result = listPort.findByIban(withStatusesAndTypes(Set.of(),
      EnumSet.allOf(TransferTypeEnum.class)));

    assertThat(result.total()).isEqualTo(5L);
    assertThat(result.rows()).hasSize(5);
  }

  @Test
  void statusFilterKeepsOnlyTheRequestedStatusesInBothTables() {
    mixedStatuses();

    StoredTransferListDto completed = listPort.findByIban(withStatusesAndTypes(
      EnumSet.of(TransferStatusEnum.COMPLETED), EnumSet.allOf(TransferTypeEnum.class)));
    StoredTransferListDto rejectedOrSent = listPort.findByIban(withStatusesAndTypes(
      EnumSet.of(TransferStatusEnum.REJECTED, TransferStatusEnum.SENT), EnumSet.allOf(TransferTypeEnum.class)));

    assertThat(ids(completed)).containsExactly("l-ext-completed", "l-int-completed");
    assertThat(completed.total()).isEqualTo(2L);
    assertThat(ids(rejectedOrSent)).containsExactly("l-ext-rejected", "l-ext-sent", "l-int-rejected");
    assertThat(rejectedOrSent.total()).isEqualTo(3L);
  }

  @Test
  void aStatusWithNoTransfersGivesAnEmptyResult() {
    mixedStatuses();

    StoredTransferListDto result = listPort.findByIban(withStatusesAndTypes(
      EnumSet.of(TransferStatusEnum.PENDING), EnumSet.allOf(TransferTypeEnum.class)));

    assertThat(result.rows()).isEmpty();
    assertThat(result.total()).isZero();
  }

  @Test
  void typeFilterReadsOnlyTheRequestedTable() {
    mixedStatuses();

    StoredTransferListDto internal = listPort.findByIban(withStatusesAndTypes(Set.of(),
      EnumSet.of(TransferTypeEnum.INTERNAL)));
    StoredTransferListDto external = listPort.findByIban(withStatusesAndTypes(Set.of(),
      EnumSet.of(TransferTypeEnum.EXTERNAL)));

    assertThat(ids(internal)).containsExactly("l-int-rejected", "l-int-completed");
    assertThat(internal.total()).isEqualTo(2L);
    assertThat(internal.rows()).allSatisfy(row -> assertThat(row.type()).isEqualTo(TransferTypeEnum.INTERNAL));
    assertThat(ids(external)).containsExactly("l-ext-completed", "l-ext-rejected", "l-ext-sent");
    assertThat(external.total()).isEqualTo(3L);
    assertThat(external.rows()).allSatisfy(row -> assertThat(row.type()).isEqualTo(TransferTypeEnum.EXTERNAL));
  }

  @Test
  void statusAndTypeFiltersCombine() {
    mixedStatuses();

    StoredTransferListDto result = listPort.findByIban(withStatusesAndTypes(
      EnumSet.of(TransferStatusEnum.REJECTED), EnumSet.of(TransferTypeEnum.EXTERNAL)));

    assertThat(ids(result)).containsExactly("l-ext-rejected");
    assertThat(result.total()).isEqualTo(1L);
  }

  // ------------------------------------------------------------------ date range

  /** An internal and an external transfer at 08:00, 09:00 and 10:00 UTC, all involving X. */
  private void sixTransfersOnThreeInstants() {
    internal("l-int-0", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));
    internal("l-int-1", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(1));
    internal("l-int-2", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(2));
    external("l-ext-0", X, EXTERNAL, "1.00", TransferStatusEnum.COMPLETED, at(0));
    external("l-ext-1", X, EXTERNAL, "1.00", TransferStatusEnum.COMPLETED, at(1));
    external("l-ext-2", X, EXTERNAL, "1.00", TransferStatusEnum.COMPLETED, at(2));
  }

  @Test
  void fromIsInclusiveInBothTables() {
    sixTransfersOnThreeInstants();

    StoredTransferListDto result = listPort.findByIban(withDates(at(1), null));

    assertThat(ids(result)).containsExactlyInAnyOrder("l-int-1", "l-int-2", "l-ext-1", "l-ext-2");
    assertThat(result.total()).isEqualTo(4L);
  }

  @Test
  void toIsExclusiveInBothTables() {
    sixTransfersOnThreeInstants();

    StoredTransferListDto result = listPort.findByIban(withDates(null, at(2)));

    assertThat(ids(result)).containsExactlyInAnyOrder("l-int-0", "l-int-1", "l-ext-0", "l-ext-1");
    assertThat(result.total()).isEqualTo(4L);
  }

  @Test
  void dateRangeFromInclusiveToExclusive() {
    sixTransfersOnThreeInstants();

    StoredTransferListDto result = listPort.findByIban(withDates(at(1), at(2)));

    assertThat(ids(result)).containsExactlyInAnyOrder("l-int-1", "l-ext-1");
    assertThat(result.total()).isEqualTo(2L);
  }

  @Test
  void aRangeOneSecondAroundTheBoundariesExcludesAndIncludesAsExpected() {
    sixTransfersOnThreeInstants();

    StoredTransferListDto afterFrom = listPort.findByIban(withDates(at(1).plusSeconds(1), null));
    StoredTransferListDto beforeTo = listPort.findByIban(withDates(null, at(1).minusSeconds(1)));

    assertThat(ids(afterFrom)).containsExactlyInAnyOrder("l-int-2", "l-ext-2");
    assertThat(ids(beforeTo)).containsExactlyInAnyOrder("l-int-0", "l-ext-0");
  }

  // ------------------------------------------------------------------ the two clocks

  private Instant storedInstantOfTheInternalTransfer(String id) {
    Timestamp stored = jdbcTemplate.queryForObject("select created_at from transfer where id = ?", Timestamp.class,
      id);
    assertThat(stored).isNotNull();
    return stored.toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant();
  }

  @Test
  void internalLocalTimeAndExternalUtcAreMergedOnTheSameClock() {
    // the internal transfer keeps the default of the database
    transferRepository.save(new Transfer("l-int-default", X, Y, new Money(new BigDecimal("1.00"), "EUR"),
      TransferStatusEnum.COMPLETED));
    entityManager.flush();
    Instant internalInstant = storedInstantOfTheInternalTransfer("l-int-default");
    entityManager.clear();
    // the external one is one second newer than the internal one
    external("l-ext-newer", X, EXTERNAL, "2.00", TransferStatusEnum.COMPLETED, internalInstant.plusSeconds(1));

    StoredTransferListDto result = listPort.findByIban(all(X));

    assertThat(ids(result)).containsExactly("l-ext-newer", "l-int-default");
    assertThat(result.rows().get(0).createdAt()).isEqualTo(internalInstant.plusSeconds(1));
    assertThat(result.rows().get(1).createdAt()).isEqualTo(internalInstant);
    // the default of the database is the current time, read as an Instant of the JVM time zone
    assertThat(Duration.between(internalInstant, Instant.now()).abs()).isLessThan(Duration.ofMinutes(10));
  }

  @Test
  void anInternalTransferOneSecondNewerThanTheExternalOneComesFirst() {
    transferRepository.save(new Transfer("l-int-default", X, Y, new Money(new BigDecimal("1.00"), "EUR"),
      TransferStatusEnum.COMPLETED));
    entityManager.flush();
    Instant internalInstant = storedInstantOfTheInternalTransfer("l-int-default");
    entityManager.clear();
    external("l-ext-older", X, EXTERNAL, "2.00", TransferStatusEnum.COMPLETED, internalInstant.minusSeconds(1));

    StoredTransferListDto result = listPort.findByIban(all(X));

    assertThat(ids(result)).containsExactly("l-int-default", "l-ext-older");
  }

  // ------------------------------------------------------------------ pagination and ordering

  /** Five outgoing transfers of X alternating between the tables, oldest first: int, ext, int, ext, int. */
  private void fiveAlternatingTransfers() {
    internal("l-r1", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));
    external("l-r2", X, EXTERNAL, "2.00", TransferStatusEnum.COMPLETED, at(1));
    internal("l-r3", X, Y, "3.00", TransferStatusEnum.COMPLETED, at(2));
    external("l-r4", X, EXTERNAL, "4.00", TransferStatusEnum.COMPLETED, at(3));
    internal("l-r5", X, Y, "5.00", TransferStatusEnum.COMPLETED, at(4));
  }

  @Test
  void paginationAcrossTheTwoTables() {
    fiveAlternatingTransfers();

    StoredTransferListDto result = listPort.findByIban(withPaging(2, 2));

    assertThat(ids(result)).containsExactly("l-r3", "l-r2");
    assertThat(result.total()).isEqualTo(5L);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
    "0|2|l-r5,l-r4",
    "2|2|l-r3,l-r2",
    "4|2|l-r1",
    "5|2|",
    "1|1|l-r4",
    "0|100|l-r5,l-r4,l-r3,l-r2,l-r1"
  })
  void everyPageIsASliceOfTheGlobalOrderWithTheExactTotal(int offset, int size, String expectedIds) {
    fiveAlternatingTransfers();

    StoredTransferListDto result = listPort.findByIban(withPaging(offset, size));

    List<String> expected = expectedIds == null ? List.of() : List.of(expectedIds.split(","));
    assertThat(ids(result)).containsExactlyElementsOf(expected);
    assertThat(result.total()).isEqualTo(5L);
  }

  @Test
  void aPageDeepInOneTableStillComesOutInGlobalOrder() {
    internal("l-a1", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));
    internal("l-a2", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(1));
    internal("l-a3", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(2));
    internal("l-a4", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(3));
    external("l-b1", X, EXTERNAL, "1.00", TransferStatusEnum.COMPLETED, at(4));

    StoredTransferListDto result = listPort.findByIban(withPaging(1, 3));

    assertThat(ids(result)).containsExactly("l-a4", "l-a3", "l-a2");
    assertThat(result.total()).isEqualTo(5L);
  }

  @Test
  void tieOnDateIsOrderedByIdDescending() {
    internal("l-tie-a", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));
    internal("l-tie-b", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));
    external("l-tie-c", X, EXTERNAL, "1.00", TransferStatusEnum.COMPLETED, at(0));
    external("l-tie-d", X, EXTERNAL, "1.00", TransferStatusEnum.COMPLETED, at(0));

    StoredTransferListDto all = listPort.findByIban(all(X));
    StoredTransferListDto secondAndThird = listPort.findByIban(withPaging(1, 2));

    assertThat(ids(all)).containsExactly("l-tie-d", "l-tie-c", "l-tie-b", "l-tie-a");
    assertThat(ids(secondAndThird)).containsExactly("l-tie-c", "l-tie-b");
    assertThat(secondAndThird.total()).isEqualTo(4L);
  }

  @Test
  void unknownIbanIsEmpty() {
    threeTransfersOfX();

    StoredTransferListDto result = listPort.findByIban(all(UNKNOWN));

    assertThat(result.rows()).isEmpty();
    assertThat(result.total()).isZero();
  }

  @Test
  void anIbanWithNoTransfersAtAllOnAnEmptyDatabaseIsEmpty() {
    StoredTransferListDto result = listPort.findByIban(all(X));

    assertThat(result.rows()).isEmpty();
    assertThat(result.total()).isZero();
  }

  // ------------------------------------------------------------------ the read-only column

  @Test
  void createdAtIsReadOnlyOnTheInternalEntity() {
    LocalDateTime fixed = localTimeOf(at(0));
    internal("l-readonly", X, Y, "1.00", TransferStatusEnum.COMPLETED, at(0));

    TransferEntity entity = springDataTransferRepository.findById("l-readonly").orElseThrow();

    assertThat(entity.getCreatedAt()).isEqualTo(fixed);

    // even if the field is changed in memory, saving the entity must not write the column
    ReflectionTestUtils.setField(entity, "createdAt", fixed.plusDays(30));
    springDataTransferRepository.saveAndFlush(entity);
    entityManager.clear();
    Timestamp stored = jdbcTemplate.queryForObject("select created_at from transfer where id = ?", Timestamp.class,
      "l-readonly");
    assertThat(stored).isNotNull();
    assertThat(stored.toLocalDateTime()).isEqualTo(fixed);
  }

  @Test
  void anInternalTransferInsertedByTheApplicationGetsTheDatabaseDefaultDate() {
    springDataTransferRepository.saveAndFlush(new TransferEntity("l-default", X.value(), Y.value(),
      new BigDecimal("1.00"), "EUR", TransferStatusEnum.COMPLETED));
    entityManager.clear();

    TransferEntity entity = springDataTransferRepository.findById("l-default").orElseThrow();

    assertThat(entity.getCreatedAt()).isNotNull();
    Instant created = entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
    assertThat(Duration.between(created, Instant.now()).abs()).isLessThan(Duration.ofMinutes(10));
  }

  // ------------------------------------------------------------------ null

  @Test
  void nullCriteriaIsRefused() {
    assertThatThrownBy(() -> listPort.findByIban(null)).isInstanceOf(NullPointerException.class);
  }
}
