package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.command.ListTransfersCommand;
import com.bank.transfer.application.dto.StoredTransferListDto;
import com.bank.transfer.application.dto.StoredTransferRowDto;
import com.bank.transfer.application.dto.TransferDirectionEnum;
import com.bank.transfer.application.dto.TransferListCriteria;
import com.bank.transfer.application.dto.TransferListItemDto;
import com.bank.transfer.application.dto.TransferPageDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.port.out.TransferListQueryOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The out port has a single method, so a lambda that records the criteria is enough. */
class ListTransfersServiceImplTest {

  private static final String IBAN = "IT60X0542811101000000000141";
  private static final String OTHER_OWN_IBAN = "IT60X0542811101000000000134";
  private static final String EXTERNAL_IBAN = "IT60X0306911101000000000001";
  private static final String OTHER_OWN_MASKED = "IT60" + "*".repeat(19) + "0134";
  private static final String EXTERNAL_MASKED = "IT60" + "*".repeat(19) + "0001";
  private static final Instant CREATED_AT = Instant.parse("2026-10-19T08:30:00Z");

  private final List<TransferListCriteria> receivedCriteria = new ArrayList<>();
  private StoredTransferListDto answer = new StoredTransferListDto(List.of(), 0);

  private ListTransfersServiceImpl service() {
    TransferListQueryOutPort port = criteria -> {
      receivedCriteria.add(criteria);
      return answer;
    };
    return new ListTransfersServiceImpl(port);
  }

  private static ListTransfersCommand onlyIban(String iban) {
    return new ListTransfersCommand(iban, null, null, null, null, null, null, null);
  }

  private static ListTransfersCommand withPaging(String page, String size) {
    return new ListTransfersCommand(IBAN, null, null, null, null, null, page, size);
  }

  private static StoredTransferRowDto row(String id, TransferTypeEnum type, String source, String dest,
    String amount, TransferStatusEnum status) {
    return new StoredTransferRowDto(id, type, source, dest, new BigDecimal(amount), "EUR", status, CREATED_AT);
  }

  // ------------------------------------------------------------------ defaults and normalization

  @Test
  void defaultsAreAllDirectionsAllStatusesBothTypesFirstPageOf20() {
    TransferPageDto page = service().listTransfers(onlyIban(IBAN));

    assertThat(receivedCriteria).hasSize(1);
    TransferListCriteria criteria = receivedCriteria.get(0);
    assertThat(criteria.iban().value()).isEqualTo(IBAN);
    assertThat(criteria.direction()).isEqualTo(TransferDirectionEnum.ALL);
    assertThat(criteria.statuses()).isEmpty();
    assertThat(criteria.types()).containsExactlyInAnyOrder(TransferTypeEnum.INTERNAL, TransferTypeEnum.EXTERNAL);
    assertThat(criteria.from()).isNull();
    assertThat(criteria.to()).isNull();
    assertThat(criteria.offset()).isZero();
    assertThat(criteria.size()).isEqualTo(20);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(20);
  }

  @Test
  void ibanIsNormalized() {
    service().listTransfers(onlyIban("  it60 x054 2811 1010 0000 0000 141 "));

    assertThat(receivedCriteria.get(0).iban().value()).isEqualTo(IBAN);
  }

  @Test
  void aNullListOfStatusesInTheCommandMeansNoStatusFilter() {
    ListTransfersCommand command = new ListTransfersCommand(IBAN, null, null, null, null, null, null, null);

    service().listTransfers(command);

    assertThat(command.statuses()).isEmpty();
    assertThat(receivedCriteria.get(0).statuses()).isEmpty();
  }

  // ------------------------------------------------------------------ mapping of the rows

  @Test
  void outgoingAndIncomingAreRelativeToTheIban() {
    answer = new StoredTransferListDto(List.of(
      row("t-out-ext", TransferTypeEnum.EXTERNAL, IBAN, EXTERNAL_IBAN, "59.73", TransferStatusEnum.COMPLETED),
      row("t-in-int", TransferTypeEnum.INTERNAL, OTHER_OWN_IBAN, IBAN, "32.46", TransferStatusEnum.COMPLETED),
      row("t-out-int", TransferTypeEnum.INTERNAL, IBAN, OTHER_OWN_IBAN, "5.00", TransferStatusEnum.REJECTED)), 3);

    TransferPageDto page = service().listTransfers(onlyIban(IBAN));

    assertThat(page.items()).extracting(TransferListItemDto::transferId)
      .containsExactly("t-out-ext", "t-in-int", "t-out-int");
    assertThat(page.items()).extracting(TransferListItemDto::direction)
      .containsExactly("OUTGOING", "INCOMING", "OUTGOING");
    assertThat(page.items()).extracting(TransferListItemDto::counterpartyIbanMasked)
      .containsExactly(EXTERNAL_MASKED, OTHER_OWN_MASKED, OTHER_OWN_MASKED);
    assertThat(page.items()).extracting(TransferListItemDto::type)
      .containsExactly("EXTERNAL", "INTERNAL", "INTERNAL");
    assertThat(page.items()).extracting(TransferListItemDto::createdAt)
      .containsOnly(CREATED_AT);
  }

  @Test
  void directionIsComputedOnTheNormalizedIbanEvenIfTheRequestWritesItDifferently() {
    answer = new StoredTransferListDto(List.of(
      row("t-out", TransferTypeEnum.EXTERNAL, IBAN, EXTERNAL_IBAN, "59.73", TransferStatusEnum.COMPLETED),
      row("t-in", TransferTypeEnum.INTERNAL, OTHER_OWN_IBAN, IBAN, "32.46", TransferStatusEnum.COMPLETED)), 2);

    TransferPageDto page = service().listTransfers(onlyIban("it60x0542811101000000000141"));

    assertThat(page.items()).extracting(TransferListItemDto::direction).containsExactly("OUTGOING", "INCOMING");
    assertThat(page.items()).extracting(TransferListItemDto::counterpartyIbanMasked)
      .containsExactly(EXTERNAL_MASKED, OTHER_OWN_MASKED);
  }

  @Test
  void theMaskedCounterpartyNeverContainsTheMiddleOfTheOriginalIban() {
    answer = new StoredTransferListDto(List.of(
      row("t-out", TransferTypeEnum.EXTERNAL, IBAN, EXTERNAL_IBAN, "59.73", TransferStatusEnum.COMPLETED)), 1);

    TransferPageDto page = service().listTransfers(onlyIban(IBAN));

    assertThat(page.items().get(0).counterpartyIbanMasked())
      .doesNotContain(EXTERNAL_IBAN.substring(4, 23))
      .doesNotContain(IBAN);
    assertThat(page.items().toString()).doesNotContain(IBAN).doesNotContain(EXTERNAL_IBAN);
  }

  @Test
  void amountHasTwoDecimalsAndStatusIsItsName() {
    answer = new StoredTransferListDto(List.of(
      row("t-1", TransferTypeEnum.INTERNAL, IBAN, OTHER_OWN_IBAN, "10.5000", TransferStatusEnum.COMPLETED)), 1);

    TransferListItemDto item = service().listTransfers(onlyIban(IBAN)).items().get(0);

    assertThat(item.amount()).isEqualByComparingTo("10.50");
    assertThat(item.amount().scale()).isEqualTo(2);
    assertThat(item.status()).isEqualTo("COMPLETED");
    assertThat(item.currency()).isEqualTo("EUR");
  }

  @ParameterizedTest
  @EnumSource(TransferStatusEnum.class)
  void everyStatusIsReturnedAsItsName(TransferStatusEnum status) {
    answer = new StoredTransferListDto(List.of(
      row("t-1", TransferTypeEnum.EXTERNAL, IBAN, EXTERNAL_IBAN, "1.00", status)), 1);

    TransferListItemDto item = service().listTransfers(onlyIban(IBAN)).items().get(0);

    assertThat(item.status()).isEqualTo(status.name());
  }

  // ------------------------------------------------------------------ filters

  @Test
  void filtersArePassedToThePort() {
    ListTransfersCommand command = new ListTransfersCommand(IBAN, "OUTGOING", List.of("COMPLETED", "REJECTED"),
      "EXTERNAL", "2026-10-01T00:00:00Z", "2026-10-19T00:00:00Z", "1", "50");

    service().listTransfers(command);

    TransferListCriteria criteria = receivedCriteria.get(0);
    assertThat(criteria.direction()).isEqualTo(TransferDirectionEnum.OUTGOING);
    assertThat(criteria.statuses())
      .containsExactlyInAnyOrder(TransferStatusEnum.COMPLETED, TransferStatusEnum.REJECTED);
    assertThat(criteria.types()).containsExactly(TransferTypeEnum.EXTERNAL);
    assertThat(criteria.from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    assertThat(criteria.to()).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
    assertThat(criteria.offset()).isEqualTo(50);
    assertThat(criteria.size()).isEqualTo(50);
  }

  @ParameterizedTest
  @EnumSource(TransferDirectionEnum.class)
  void everyDirectionIsAccepted(TransferDirectionEnum direction) {
    service().listTransfers(new ListTransfersCommand(IBAN, direction.name(), null, null, null, null, null, null));

    assertThat(receivedCriteria.get(0).direction()).isEqualTo(direction);
  }

  @ParameterizedTest
  @EnumSource(TransferTypeEnum.class)
  void everyTypeIsAccepted(TransferTypeEnum type) {
    service().listTransfers(new ListTransfersCommand(IBAN, null, null, type.name(), null, null, null, null));

    assertThat(receivedCriteria.get(0).types()).containsExactly(type);
  }

  @ParameterizedTest
  @EnumSource(TransferStatusEnum.class)
  void everyStatusIsAcceptedAsAFilterIncludingPending(TransferStatusEnum status) {
    service().listTransfers(new ListTransfersCommand(IBAN, null, List.of(status.name()), null, null, null, null,
      null));

    assertThat(receivedCriteria.get(0).statuses()).containsExactly(status);
  }

  @Test
  void aDateBoundCanBeGivenWithoutTheOther() {
    service().listTransfers(new ListTransfersCommand(IBAN, null, null, null, "2026-10-01T00:00:00Z", null, null,
      null));
    service().listTransfers(new ListTransfersCommand(IBAN, null, null, null, null, "2026-10-19T00:00:00Z", null,
      null));

    assertThat(receivedCriteria.get(0).from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    assertThat(receivedCriteria.get(0).to()).isNull();
    assertThat(receivedCriteria.get(1).from()).isNull();
    assertThat(receivedCriteria.get(1).to()).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
  }

  @Test
  void anInstantWithFractionalSecondsIsAccepted() {
    service().listTransfers(new ListTransfersCommand(IBAN, null, null, null, "2026-10-19T00:00:00.250Z",
      "2026-10-19T00:00:01Z", null, null));

    assertThat(receivedCriteria.get(0).from()).isEqualTo(Instant.parse("2026-10-19T00:00:00.250Z"));
  }

  // ------------------------------------------------------------------ paging

  @Test
  void pageAndSizeBecomeOffsetAndSize() {
    service().listTransfers(withPaging("2", "10"));

    TransferListCriteria criteria = receivedCriteria.get(0);
    assertThat(criteria.offset()).isEqualTo(20);
    assertThat(criteria.size()).isEqualTo(10);
  }

  @Test
  void hasNextIsTrueWhenThereAreMoreRowsAfterThePage() {
    answer = new StoredTransferListDto(List.of(), 31);

    TransferPageDto page = service().listTransfers(withPaging("2", "10"));

    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.totalElements()).isEqualTo(31L);
    assertThat(page.hasNext()).isTrue();
  }

  @Test
  void hasNextIsFalseWhenThePageEndsExactlyAtTheTotal() {
    answer = new StoredTransferListDto(List.of(), 30);

    TransferPageDto page = service().listTransfers(withPaging("2", "10"));

    assertThat(page.totalElements()).isEqualTo(30L);
    assertThat(page.hasNext()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"0,1", "0,100", "9,100", "49,20", "999,1"})
  void pagesInsideTheWindowOf1000AreAccepted(String page, String size) {
    service().listTransfers(withPaging(page, size));

    assertThat(receivedCriteria).hasSize(1);
    assertThat(receivedCriteria.get(0).offset()).isEqualTo(Integer.parseInt(page) * Integer.parseInt(size));
  }

  @Test
  void emptyResultIsAnEmptyPage() {
    TransferPageDto page = service().listTransfers(onlyIban(IBAN));

    assertThat(page.items()).isEmpty();
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(20);
    assertThat(page.totalElements()).isZero();
    assertThat(page.hasNext()).isFalse();
  }

  // ------------------------------------------------------------------ invalid input

  static Stream<Arguments> invalidInputs() {
    String badIbanLength = "IT60X05428";
    String badIbanChars = "IT60X05428111010000000@0001";
    return Stream.of(
      Arguments.of(onlyIban(null), "iban is required", null),
      Arguments.of(onlyIban(badIbanLength), "Malformed IBAN, it has to be 27 characters", badIbanLength),
      Arguments.of(onlyIban(badIbanChars), "Malformed IBAN, invalid characters", badIbanChars),
      Arguments.of(new ListTransfersCommand(IBAN, "SENT", null, null, null, null, null, null),
        "direction must be ALL, OUTGOING or INCOMING", "SENT"),
      Arguments.of(new ListTransfersCommand(IBAN, "sideways", null, null, null, null, null, null),
        "direction must be ALL, OUTGOING or INCOMING", "sideways"),
      Arguments.of(new ListTransfersCommand(IBAN, null, List.of("COMPLETED", "BOGUS"), null, null, null, null,
        null), "status is not valid", "BOGUS"),
      Arguments.of(new ListTransfersCommand(IBAN, null, List.of("done-ish"), null, null, null, null, null),
        "status is not valid", "done-ish"),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, "OTHER", null, null, null, null),
        "type must be INTERNAL or EXTERNAL", "OTHER"),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, null, "yesterday", null, null, null),
        "from and to must be ISO-8601 instants", "yesterday"),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, null, null, "tomorrow-ish", null, null),
        "from and to must be ISO-8601 instants", "tomorrow-ish"),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, null, "2026-10-19", null, null, null),
        "from and to must be ISO-8601 instants", "2026-10-19"),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, null, null, "2026-10-19T25:00:00Z", null, null),
        "from and to must be ISO-8601 instants", "2026-10-19T25:00:00Z"),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, null, "2026-10-19T00:00:00Z",
        "2026-10-19T00:00:00Z", null, null), "from must be before to", null),
      Arguments.of(new ListTransfersCommand(IBAN, null, null, null, "2026-10-20T00:00:00Z",
        "2026-10-19T00:00:00Z", null, null), "from must be before to", null),
      Arguments.of(withPaging("-1", null), "page must be zero or more", null),
      Arguments.of(withPaging(null, "0"), "size must be between 1 and 100", null),
      Arguments.of(withPaging(null, "101"), "size must be between 1 and 100", "101"),
      Arguments.of(withPaging(null, "-5"), "size must be between 1 and 100", "-5"),
      Arguments.of(withPaging("10", "100"), "page window too large", null),
      Arguments.of(withPaging("1000", "1"), "page window too large", "1000"),
      Arguments.of(withPaging("50", "20"), "page window too large", null));
  }

  @ParameterizedTest
  @MethodSource("invalidInputs")
  void invalidInputsAreRefusedWithFixedMessages(ListTransfersCommand command, String expectedMessage,
    String receivedValue) {
    ListTransfersServiceImpl service = service();

    assertThatThrownBy(() -> service.listTransfers(command))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage(expectedMessage)
      .satisfies(e -> {
        if (receivedValue != null) {
          assertThat(e.getMessage()).doesNotContain(receivedValue);
        }
      });
    assertThat(receivedCriteria).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  void aBlankIbanIsRefusedBeforeTheLookup(String blank) {
    ListTransfersServiceImpl service = service();

    assertThatThrownBy(() -> service.listTransfers(onlyIban(blank)))
      .isInstanceOf(IllegalArgumentException.class);
    assertThat(receivedCriteria).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "1.5", "99999999999"})
  void aNonNumericPageOrSizeIsRefusedWithoutEchoingTheValue(String value) {
    ListTransfersServiceImpl service = service();

    assertThatThrownBy(() -> service.listTransfers(withPaging(value, null)))
      .isInstanceOf(IllegalArgumentException.class)
      .satisfies(e -> assertThat(e.getMessage()).doesNotContain(value));
    assertThatThrownBy(() -> service.listTransfers(withPaging(null, value)))
      .isInstanceOf(IllegalArgumentException.class)
      .satisfies(e -> assertThat(e.getMessage()).doesNotContain(value));
    assertThat(receivedCriteria).isEmpty();
  }
}
