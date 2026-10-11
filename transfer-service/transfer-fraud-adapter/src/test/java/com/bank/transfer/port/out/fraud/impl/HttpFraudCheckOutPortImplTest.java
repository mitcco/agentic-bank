package com.bank.transfer.port.out.fraud.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.domain.FraudStatusEnum;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class HttpFraudCheckOutPortImplTest {

  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";
  private static final String URL = "http://fraud.test/fraud-check";

  /** Body of every error response: it deliberately carries the IBAN and the amount, to prove they never leak. */
  private static final String LEAKY_TEXT = "boom for " + IBAN_1 + " amount 100.25";

  private MockRestServiceServer server;
  private HttpFraudCheckOutPortImpl port;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://fraud.test");
    server = MockRestServiceServer.bindTo(builder).build();
    port = new HttpFraudCheckOutPortImpl(builder.build());
  }

  private FraudCheckResultDto check() {
    return port.checkFraud(ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR");
  }

  private static ResponseCreator json(String body) {
    return withSuccess(body, MediaType.APPLICATION_JSON);
  }

  private static String allowedBody() {
    return "{\"transferId\":\"" + ID + "\",\"status\":\"ALLOWED\"}";
  }

  private void expectPostAndRespond(ResponseCreator response) {
    server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andRespond(response);
  }

  // ---------------------------------------------------------------- happy paths

  @Test
  void h1_allowedResponseGivesAllowedWithoutReasonsAndTheRequestIsAJsonPostToFraudCheck() {
    server.expect(requestTo(URL))
      .andExpect(method(HttpMethod.POST))
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andRespond(json(allowedBody()));

    FraudCheckResultDto result = check();

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.ALLOWED);
    assertThat(result.reasons()).isEmpty();
    server.verify();
  }

  @Test
  void h2_requestBodyHasExactlyTheFiveFieldsOfTheContract() {
    server.expect(requestTo(URL))
      .andExpect(content().json("""
        {"transferId":"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93",
         "sourceIban":"IT60X0542811101000000000001",
         "destinationIban":"IT60X0542811101000000000002",
         "amount":100.25,
         "currency":"EUR"}""", JsonCompareMode.STRICT))
      .andExpect(request -> assertThat(body(request)).contains("\"amount\":100.25").doesNotContain("\"amount\":\""))
      .andRespond(json(allowedBody()));

    check();

    server.verify();
  }

  @ParameterizedTest(name = "amount {0} is sent as {1}")
  @CsvSource({
    "10.10, '\"amount\":10.10'",
    "30, '\"amount\":30'",
    "1E+3, '\"amount\":1000'"
  })
  void h3_amountIsSentAsAPlainJsonNumberWithoutExponent(String amount, String expectedFragment) {
    server.expect(requestTo(URL))
      .andExpect(request -> {
        String body = body(request);
        assertThat(body).contains(expectedFragment);
        assertThat(body).doesNotContainPattern("\"amount\":[0-9.]*[eE]");
        assertThat(body).doesNotContain("\"amount\":\"");
      })
      .andRespond(json(allowedBody()));

    port.checkFraud(ID, IBAN_1, IBAN_2, new BigDecimal(amount), "EUR");

    server.verify();
  }

  @Test
  void h4_rejectedResponseGivesRejectedWithTheSameReasonsInTheSameOrder() {
    expectPostAndRespond(json("""
      {"transferId":"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93","status":"REJECTED",
       "reasons":["Source IBAN is locked","Destination IBAN is locked"]}"""));

    FraudCheckResultDto result = check();

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly("Source IBAN is locked", "Destination IBAN is locked");
  }

  @Test
  void h5_unknownFieldsInTheResponseAreIgnored() {
    expectPostAndRespond(json("""
      {"transferId":"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93","status":"ALLOWED",
       "score":0.9,"extra":{"a":[1,2,{"b":null}]}}"""));

    FraudCheckResultDto result = check();

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.ALLOWED);
    assertThat(result.reasons()).isEmpty();
  }

  @ParameterizedTest(name = "ALLOWED with reasons {0}")
  @CsvSource(delimiter = '|', value = {"[\"x\"]", "[]"})
  void h6_allowedWithReasonsIsStillAllowedAndTheReasonsAreDropped(String reasons) {
    expectPostAndRespond(json("{\"transferId\":\"" + ID + "\",\"status\":\"ALLOWED\",\"reasons\":" + reasons + "}"));

    FraudCheckResultDto result = check();

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.ALLOWED);
    assertThat(result.reasons()).isEmpty();
  }

  // ---------------------------------------------------------------- "unavailable" outcomes

  /** A named response, so that the parameterized test reports are readable. */
  record Case(String name, ResponseCreator response) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Case error(HttpStatus status, String code) {
    return new Case("HTTP " + status.value() + " " + code, withStatus(status).contentType(MediaType.APPLICATION_JSON)
      .body("{\"code\":\"" + code + "\",\"message\":\"" + LEAKY_TEXT + "\"}"));
  }

  private static Case okWith(String name, String body) {
    return new Case(name, withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private static String bodyWithStatus(String statusFragment, String reasonsFragment) {
    return "{\"transferId\":\"" + ID + "\"" + statusFragment + reasonsFragment + "}";
  }

  /** H7. */
  static Stream<Case> httpErrors() {
    return Stream.of(
      error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"),
      error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"),
      error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"),
      error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY"),
      error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE"),
      error(HttpStatus.NOT_FOUND, "NOT_FOUND"));
  }

  /** H8. */
  static Stream<Case> unexpectedSuccessOrRedirectCodes() {
    return Stream.of(
      new Case("HTTP 201 with an ALLOWED body", withStatus(HttpStatus.CREATED)
        .contentType(MediaType.APPLICATION_JSON).body(allowedBody())),
      new Case("HTTP 204 with an ALLOWED body", withStatus(HttpStatus.NO_CONTENT)
        .contentType(MediaType.APPLICATION_JSON).body(allowedBody())),
      new Case("HTTP 302", withStatus(HttpStatus.FOUND).header("Location", "http://elsewhere.test/fraud-check")));
  }

  /** H9. */
  static Stream<Case> unreadableBodies() {
    return Stream.of(
      okWith("plain text body declared as JSON", "testo qualunque " + LEAKY_TEXT),
      okWith("empty body", ""),
      okWith("JSON array instead of an object", "[]"),
      new Case("valid ALLOWED body with Content-Type text/plain", withSuccess(allowedBody(), MediaType.TEXT_PLAIN)));
  }

  /** H10. */
  static Stream<Case> unknownStatuses() {
    return Stream.of(
      okWith("status BLOCKED", bodyWithStatus(",\"status\":\"BLOCKED\"", "")),
      okWith("status in lower case", bodyWithStatus(",\"status\":\"allowed\"", "")),
      okWith("status missing", bodyWithStatus("", "")),
      okWith("status null", bodyWithStatus(",\"status\":null", "")));
  }

  /** H11. */
  static Stream<Case> rejectedWithoutUsableReasons() {
    return Stream.of(
      okWith("REJECTED without reasons", bodyWithStatus(",\"status\":\"REJECTED\"", "")),
      okWith("REJECTED with empty reasons", bodyWithStatus(",\"status\":\"REJECTED\"", ",\"reasons\":[]")),
      okWith("REJECTED with only null and blank reasons",
        bodyWithStatus(",\"status\":\"REJECTED\"", ",\"reasons\":[null,\" \"]")));
  }

  /** H12. */
  static Stream<Case> wrongTransferId() {
    return Stream.of(
      okWith("transferId of another transfer",
        "{\"transferId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"ALLOWED\"}"),
      okWith("transferId missing", "{\"status\":\"ALLOWED\"}"));
  }

  /** H13. */
  static Stream<Case> ioFailures() {
    return Stream.of(
      new Case("read timeout", withException(new SocketTimeoutException())),
      new Case("connect timeout", withException(new HttpConnectTimeoutException("x"))),
      new Case("connection refused", withException(new ConnectException())));
  }

  /** H7 to H13: every case that the contract calls "service unavailable". */
  static Stream<Case> allUnavailableCases() {
    return Stream.of(httpErrors(), unexpectedSuccessOrRedirectCodes(), unreadableBodies(), unknownStatuses(),
      rejectedWithoutUsableReasons(), wrongTransferId(), ioFailures()).flatMap(s -> s);
  }

  @ParameterizedTest(name = "h7 {0}")
  @MethodSource("httpErrors")
  void h7_everyNon200ErrorOfTheContractMeansUnavailable(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @ParameterizedTest(name = "h8 {0}")
  @MethodSource("unexpectedSuccessOrRedirectCodes")
  void h8_aCodeOtherThan200IsUnavailableEvenIfItLooksSuccessful(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @ParameterizedTest(name = "h9 {0}")
  @MethodSource("unreadableBodies")
  void h9_aBodyThatIsNotAJsonObjectOfTheContractIsUnavailable(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @ParameterizedTest(name = "h10 {0}")
  @MethodSource("unknownStatuses")
  void h10_aStatusThatIsNotExactlyAllowedOrRejectedIsUnavailable(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @ParameterizedTest(name = "h11 {0}")
  @MethodSource("rejectedWithoutUsableReasons")
  void h11_aRejectionWithoutAnyUsableReasonIsUnavailable(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @ParameterizedTest(name = "h12 {0}")
  @MethodSource("wrongTransferId")
  void h12_aTransferIdThatIsMissingOrDifferentFromTheOneSentIsUnavailable(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @ParameterizedTest(name = "h13 {0}")
  @MethodSource("ioFailures")
  void h13_timeoutsAndRefusedConnectionsAreUnavailable(Case c) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
  }

  // ---------------------------------------------------------------- no leaks

  @ParameterizedTest(name = "h14 {0}")
  @MethodSource("allUnavailableCases")
  void h14_exceptionAndLogsCarryTheTransferIdButNeverIbanOrAmount(Case c, CapturedOutput output) {
    expectPostAndRespond(c.response());

    assertThatThrownBy(this::check)
      .isInstanceOfSatisfying(FraudCheckUnavailableException.class, e -> {
        assertThat(e.getMessage()).contains(ID).doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25");
        assertThat(e.getCause()).isNull();
      });

    assertThat(output.getAll()).contains(ID).doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25");
  }

  @ParameterizedTest(name = "io error cause {1}")
  @MethodSource("ioErrorCauses")
  void h14b_ioErrorWarnLogNamesTheSpecificCauseClassNotTheSpringWrapperAndNeverTheMessage(
    java.io.IOException cause, String expectedSimpleName, CapturedOutput output) {
    expectPostAndRespond(withException(cause));

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);

    String warnLine = output.getAll().lines()
      .filter(line -> line.contains("IO_ERROR"))
      .findFirst()
      .orElseThrow(() -> new AssertionError("no IO_ERROR warn line in: " + output.getAll()));
    assertThat(warnLine).contains(ID).contains(expectedSimpleName).doesNotContain("ResourceAccessException");
    assertThat(output.getAll()).doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25")
      .doesNotContain("secret-detail");
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> ioErrorCauses() {
    String leakyMessage = "secret-detail " + IBAN_1 + " amount 100.25";
    return Stream.of(
      org.junit.jupiter.params.provider.Arguments.of(new SocketTimeoutException(leakyMessage), "SocketTimeoutException"),
      org.junit.jupiter.params.provider.Arguments.of(new ConnectException(leakyMessage), "ConnectException"),
      org.junit.jupiter.params.provider.Arguments.of(new HttpConnectTimeoutException(leakyMessage),
        "HttpConnectTimeoutException"));
  }

  @Test
  void h15_allowedOutcomeIsLoggedWithTheTransferIdAndTheOutcomeButNotIbanOrAmount(CapturedOutput output) {
    expectPostAndRespond(json(allowedBody()));

    check();

    assertThat(output.getAll()).contains(ID).contains("ALLOWED")
      .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25");
  }

  @Test
  void h15_rejectedOutcomeIsLoggedWithTheTransferIdTheOutcomeAndTheReasonsButNotIbanOrAmount(CapturedOutput output) {
    expectPostAndRespond(json("""
      {"transferId":"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93","status":"REJECTED",
       "reasons":["Source IBAN is locked","Destination IBAN is locked"]}"""));

    check();

    assertThat(output.getAll()).contains(ID).contains("REJECTED").contains("Source IBAN is locked")
      .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25");
  }

  @Test
  void rejectedReasonsThatAreNullOrBlankAreDroppedAndTheUsableOnesKept() {
    expectPostAndRespond(json(bodyWithStatus(",\"status\":\"REJECTED\"",
      ",\"reasons\":[null,\" \",\"Source IBAN is locked\"]")));

    FraudCheckResultDto result = check();

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly("Source IBAN is locked");
  }

  @Test
  void aNewCallAfterAFailureStillWorks() {
    expectPostAndRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    expectPostAndRespond(json(allowedBody()));

    assertThatThrownBy(this::check).isInstanceOf(FraudCheckUnavailableException.class);
    assertThat(check().decision()).isEqualTo(FraudStatusEnum.ALLOWED);
  }

  private static String body(org.springframework.http.client.ClientHttpRequest request) {
    return ((MockClientHttpRequest) request).getBodyAsString();
  }
}
