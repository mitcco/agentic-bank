package com.bank.notification.mongo.port.out.mongo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.bank.notification.domain.Iban;
import com.bank.notification.domain.Money;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import com.bank.notification.mongo.repository.TransferNotificationRepository;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Integration test against the MongoDB of Docker. It is skipped, visibly, when Mongo does not
 * answer. The database of the URI is ignored: the test always uses {@code notification_test}.
 */
class NotificationStoreOutPortImplIntegrationTest {

  private static final String DEFAULT_URI =
      "mongodb://notification:notification_dev@localhost:27018/notification?authSource=admin";
  private static final String TEST_DATABASE = "notification_test";
  private static final String COLLECTION = "transfer_notifications";
  private static final String SKIP_MESSAGE = "MongoDB not reachable: integration test skipped";

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String OTHER_TRANSFER_ID = "9d1c7e52-4b3a-4f08-8c6d-2a5e1b7f9c04";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final String REJECTED_SOURCE = "IT60X0542811101000000000099";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");

  private static MongoClient client;
  private static boolean reachable;

  private MongoTemplate template;
  private NotificationStoreOutPortImpl port;

  @BeforeAll
  static void connect() {
    requireTestDatabase(TEST_DATABASE);
    String uri = System.getenv("MONGODB_URI");
    if (uri == null || uri.isBlank()) {
      uri = DEFAULT_URI;
    }
    MongoClientSettings settings = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(
            builder -> builder.serverSelectionTimeout(2, TimeUnit.SECONDS))
        .build();
    client = MongoClients.create(settings);
    try {
      client.getDatabase("admin").runCommand(new Document("ping", 1));
      reachable = true;
    } catch (MongoTimeoutException e) {
      // Only the server selection timeout means "Mongo absent": the test is skipped.
      // An authentication error reaches runCommand as MongoSecurityException (monitoring
      // connections do not authenticate) and is not caught here, so the test fails.
      reachable = false;
    }
  }

  @AfterAll
  static void disconnect() {
    if (client != null) {
      client.close();
    }
  }

  @BeforeEach
  void setUp() {
    assumeTrue(reachable, SKIP_MESSAGE);
    template = new MongoTemplate(client, TEST_DATABASE);
    TransferNotificationRepository repository =
        new MongoRepositoryFactory(template).getRepository(TransferNotificationRepository.class);
    port = new NotificationStoreOutPortImpl(repository);
    template.getCollection(COLLECTION).deleteMany(new Document());
  }

  @AfterEach
  void tearDown() {
    if (reachable && template != null) {
      template.getCollection(COLLECTION).deleteMany(new Document());
    }
  }

  @Test
  void storesACompletedNotificationAsOneDocumentWithTheExpectedShapeAndBsonTypes() {
    port.save(completed(TRANSFER_ID, new BigDecimal("100.25")));

    assertThat(template.getCollection(COLLECTION).countDocuments()).isEqualTo(1);
    Document stored = template.getCollection(COLLECTION).find().first();
    assertThat(stored).isNotNull();
    assertThat(stored.get("_id")).isInstanceOf(String.class).isEqualTo(TRANSFER_ID);
    assertThat(stored.get("amount")).isInstanceOf(Decimal128.class);
    BigDecimal amount = ((Decimal128) stored.get("amount")).bigDecimalValue();
    assertThat(amount).isEqualByComparingTo("100.25");
    assertThat(amount.scale()).isEqualTo(2);
    assertThat(stored.get("occurredAt")).isInstanceOf(Date.class)
        .isEqualTo(Date.from(OCCURRED_AT));
    assertThat(stored.get("status")).isInstanceOf(String.class).isEqualTo("COMPLETED");
    assertThat(stored.get("reasons")).isInstanceOf(List.class);
    assertThat(stored.getList("reasons", String.class)).isEmpty();
    assertThat(stored.getString("sourceIban")).isEqualTo(SOURCE);
    assertThat(stored.getString("destinationIban")).isEqualTo(DESTINATION);
    assertThat(stored.getString("currency")).isEqualTo("EUR");
    assertThat(stored.keySet().stream().filter(key -> !key.equals("_class")).toList())
        .containsExactlyInAnyOrder("_id", "sourceIban", "destinationIban", "amount", "currency",
            "status", "reasons", "occurredAt");
  }

  @Test
  void storesARejectedNotificationWithTheReasonsInOrder() {
    port.save(rejected(TRANSFER_ID, new BigDecimal("20000.00")));

    Document stored = template.getCollection(COLLECTION).find().first();
    assertThat(stored).isNotNull();
    assertThat(stored.getString("status")).isEqualTo("REJECTED");
    assertThat(stored.getList("reasons", String.class))
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  @Test
  void replacesTheDocumentWhenTheSameTransferIdIsSavedAgain() {
    port.save(completed(TRANSFER_ID, new BigDecimal("100.25")));

    assertThatCode(() -> port.save(rejected(TRANSFER_ID, new BigDecimal("200.00"))))
        .doesNotThrowAnyException();

    assertThat(template.getCollection(COLLECTION).countDocuments()).isEqualTo(1);
    Document stored = template.getCollection(COLLECTION).find().first();
    assertThat(stored).isNotNull();
    assertThat(stored.getString("_id")).isEqualTo(TRANSFER_ID);
    assertThat(stored.getString("status")).isEqualTo("REJECTED");
    assertThat(((Decimal128) stored.get("amount")).bigDecimalValue())
        .isEqualByComparingTo("200.00");
    assertThat(stored.getList("reasons", String.class))
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  @Test
  void keepsTwoDocumentsForTwoDifferentTransferIds() {
    port.save(completed(TRANSFER_ID, new BigDecimal("100.25")));
    port.save(completed(OTHER_TRANSFER_ID, new BigDecimal("100.25")));

    assertThat(template.getCollection(COLLECTION).countDocuments()).isEqualTo(2);
  }

  /**
   * Guard against pointing the tests at the real database.
   */
  private static void requireTestDatabase(String name) {
    if ("notification".equals(name)) {
      throw new IllegalStateException("Integration tests must not use the notification database");
    }
  }

  private static TransferNotification completed(String transferId, BigDecimal amount) {
    return new TransferNotification(transferId, new Iban(SOURCE), new Iban(DESTINATION),
        new Money(amount, "EUR"), TransferOutcome.COMPLETED, List.of(), OCCURRED_AT);
  }

  private static TransferNotification rejected(String transferId, BigDecimal amount) {
    return new TransferNotification(transferId, new Iban(REJECTED_SOURCE), new Iban(DESTINATION),
        new Money(amount, "EUR"), TransferOutcome.REJECTED,
        List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked"), OCCURRED_AT);
  }
}
