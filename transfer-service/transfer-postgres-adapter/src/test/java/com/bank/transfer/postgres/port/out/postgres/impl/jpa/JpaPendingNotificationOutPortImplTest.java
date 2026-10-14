package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.PendingNotificationDto;
import com.bank.transfer.application.exception.InvalidOutboxRowException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading and deleting the rows of the outbox. No test transaction: every call of the adapter must commit by itself,
 * as it does in production (rows are inserted with JdbcTemplate, without id, so the database assigns it).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaPendingNotificationOutPortImpl.class)
class JpaPendingNotificationOutPortImplTest {

  private static final String T1 = "aaaaaaaa-0000-4000-8000-000000000001";
  private static final String T2 = "aaaaaaaa-0000-4000-8000-000000000002";
  private static final String T3 = "aaaaaaaa-0000-4000-8000-000000000003";
  private static final String T4 = "aaaaaaaa-0000-4000-8000-000000000004";

  @Autowired
  private JpaPendingNotificationOutPortImpl adapter;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  @AfterEach
  void cleanOutbox() {
    jdbcTemplate.update("DELETE FROM outbox");
  }

  private long insert(String transferId, String payload) {
    jdbcTemplate.update("INSERT INTO outbox (transfer_id, payload) VALUES (?, ?)", transferId, payload);
    Long id = jdbcTemplate.queryForObject("SELECT id FROM outbox WHERE transfer_id = ?", Long.class, transferId);
    assertThat(id).isNotNull();
    return id;
  }

  private int countById(long id) {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox WHERE id = ?", Integer.class, id);
    assertThat(count).isNotNull();
    return count;
  }

  private static String payloadOf(String transferId) {
    return "{\"transferId\":\"" + transferId + "\",\"status\":\"COMPLETED\"}";
  }

  // P1
  @Test
  void findPendingReturnsTheOldestRowsFirstUpToTheLimit() {
    long id1 = insert(T1, payloadOf(T1));
    long id2 = insert(T2, payloadOf(T2));
    insert(T3, payloadOf(T3));

    List<PendingNotificationDto> found = adapter.findPending(2);

    assertThat(found).hasSize(2);
    assertThat(found.get(0).id()).isEqualTo(id1);
    assertThat(found.get(0).transferId()).isEqualTo(T1);
    assertThat(found.get(0).payload()).isEqualTo(payloadOf(T1));
    assertThat(found.get(1).id()).isEqualTo(id2);
    assertThat(found.get(1).transferId()).isEqualTo(T2);
    assertThat(found.get(1).payload()).isEqualTo(payloadOf(T2));
    assertThat(id1).isLessThan(id2);
  }

  // P2
  @Test
  void orderFollowsTheIdEvenAfterADeletionAndANewInsert() {
    long id1 = insert(T1, payloadOf(T1));
    long id2 = insert(T2, payloadOf(T2));
    long id3 = insert(T3, payloadOf(T3));
    jdbcTemplate.update("DELETE FROM outbox WHERE id = ?", id2);
    long id4 = insert(T4, payloadOf(T4));

    List<PendingNotificationDto> found = adapter.findPending(10);

    assertThat(found).extracting(PendingNotificationDto::id).containsExactly(id1, id3, id4);
    assertThat(found).extracting(PendingNotificationDto::transferId).containsExactly(T1, T3, T4);
    assertThat(id4).isGreaterThan(id3);
  }

  // P3
  @Test
  void aLimitGreaterThanTheRowsReturnsAllTheRows() {
    insert(T1, payloadOf(T1));
    insert(T2, payloadOf(T2));

    assertThat(adapter.findPending(100)).hasSize(2);
  }

  // P3 (boundary)
  @Test
  void aLimitEqualToTheNumberOfRowsReturnsAllTheRows() {
    insert(T1, payloadOf(T1));
    insert(T2, payloadOf(T2));

    assertThat(adapter.findPending(2)).hasSize(2);
  }

  // P4
  @Test
  void anEmptyTableGivesAnEmptyList() {
    assertThat(adapter.findPending(10)).isEmpty();
  }

  // P5
  @Test
  void deleteRemovesOnlyTheRequestedRow() {
    long id1 = insert(T1, payloadOf(T1));
    long id2 = insert(T2, payloadOf(T2));

    adapter.delete(id1);

    assertThat(countById(id1)).isZero();
    assertThat(countById(id2)).isEqualTo(1);
  }

  // P6
  @Test
  void deletingARowThatIsAlreadyGoneOrNeverExistedDoesNotFail() {
    long id1 = insert(T1, payloadOf(T1));
    adapter.delete(id1);

    assertThatCode(() -> adapter.delete(id1)).doesNotThrowAnyException();
    assertThatCode(() -> adapter.delete(987_654_321L)).doesNotThrowAnyException();
  }

  // P7
  @Test
  void deleteIsCommittedEvenIfTheCallerTransactionIsRolledBack() {
    long id1 = insert(T1, payloadOf(T1));

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      adapter.delete(id1);
      status.setRollbackOnly();
    });

    assertThat(countById(id1)).isZero();
  }

  // P8: the delete runs in a transaction of its own (it does not see what the caller has not committed yet)
  @Test
  void deleteRunsInANewTransactionThatDoesNotSeeTheCallerUncommittedRows() {
    long[] uncommittedId = new long[1];

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      // written through the connection of the caller transaction, not yet committed
      uncommittedId[0] = insert(T1, payloadOf(T1));

      adapter.delete(uncommittedId[0]);

      // a delete that had joined the caller transaction would have removed the row here
      assertThat(countById(uncommittedId[0])).isEqualTo(1);
    });

    assertThat(countById(uncommittedId[0])).isEqualTo(1);
  }

  // P9
  @Test
  void aLimitBelowOneIsRejected() {
    insert(T1, payloadOf(T1));

    assertThatThrownBy(() -> adapter.findPending(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> adapter.findPending(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  private void assertInvalidRow(long expectedRowId, String forbiddenText) {
    assertThatThrownBy(() -> adapter.findPending(10))
      .isInstanceOfSatisfying(InvalidOutboxRowException.class, e -> {
        assertThat(e.rowId()).isEqualTo(expectedRowId);
        assertThat(e.getMessage()).isEqualTo("Invalid outbox row: id=" + expectedRowId)
          .doesNotContain(forbiddenText);
      });
  }

  // invalid row (correction 4)
  @Test
  void aRowWithAnEmptyPayloadFailsWithTheIdOfTheRowAndWithoutTheTransferId() {
    long id = insert(T1, "");

    assertInvalidRow(id, T1);
  }

  // invalid row (correction 4)
  @Test
  void aRowWithAnEmptyTransferIdFailsWithTheIdOfTheRowAndWithoutThePayload() {
    String payload = "{\"marker\":\"neutral-payload-text\"}";
    long id = insert("", payload);

    assertInvalidRow(id, "neutral-payload-text");
  }

  // invalid row (correction 4)
  @Test
  void aValidRowBeforeAnInvalidOneGivesNoPartialResultAndNamesTheInvalidRow() {
    insert(T1, payloadOf(T1));
    long invalidId = insert(T2, "");
    insert(T3, payloadOf(T3));

    assertThatThrownBy(() -> adapter.findPending(10))
      .isInstanceOfSatisfying(InvalidOutboxRowException.class, e -> assertThat(e.rowId()).isEqualTo(invalidId));
  }

  // invalid row (correction 4): the limit is respected, an invalid row beyond it is not read
  @Test
  void anInvalidRowBeyondTheLimitDoesNotBlockTheRowsBeforeIt() {
    long id1 = insert(T1, payloadOf(T1));
    insert(T2, "");

    assertThat(adapter.findPending(1)).extracting(PendingNotificationDto::id).containsExactly(id1);
  }

  // invalid row (correction 4)
  @Test
  void aSingleValidRowStillWorks() {
    long id1 = insert(T1, payloadOf(T1));

    List<PendingNotificationDto> found = adapter.findPending(10);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).id()).isEqualTo(id1);
    assertThat(found.get(0).transferId()).isEqualTo(T1);
    assertThat(found.get(0).payload()).isEqualTo(payloadOf(T1));
  }
}
