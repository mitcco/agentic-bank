package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.FraudStatusEnum;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /transfers/{id} on rows stored in a state the service cannot show (amount with a significant 3rd or 4th
 * decimal, invalid IBAN): the answer is a 500 with a fixed detail and nothing sensitive in body or logs.
 * The rows are inserted by hand and removed after each test. Dedicated in-memory H2 and Spring context.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:transferquerystored;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "clearing.outcomes.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class TransferQueryStoredDataEndToEndTest {

  private static final FraudCheckResultDto ALLOWED = new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());

  @TestConfiguration
  static class AlwaysAllowedFraudCheck {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) -> ALLOWED;
    }
  }

  /** A seeded account of this bank (foreign key of external_transfer.source_iban). */
  private static final String SEEDED_SOURCE_IBAN = "IT60X0542811101000000000101";
  private static final String VALID_DEST_IBAN = "IT60X0306909606100000012345";
  /** 26 characters: one too few. */
  private static final String SHORT_DEST_IBAN = "IT60X030690960610000001234";
  private static final String BAD_AMOUNT_ID = "stored-bad-amount";
  private static final String BAD_IBAN_ID = "stored-bad-iban";
  private static final String FIXED_DETAIL = "Stored transfer data is not valid";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void removeInsertedRows() {
    jdbcTemplate.update("DELETE FROM external_transfer WHERE id IN (?, ?)", BAD_AMOUNT_ID, BAD_IBAN_ID);
  }

  private void insertExternal(String id, String destIban, String amount) {
    jdbcTemplate.update(
      "INSERT INTO external_transfer (id, source_iban, dest_iban, amount, currency, status, reason) "
        + "VALUES (?, ?, ?, ?, 'EUR', 'SENT', NULL)",
      id, SEEDED_SOURCE_IBAN, destIban, new BigDecimal(amount));
  }

  private MvcResult getTransfer(String id) throws Exception {
    return mockMvc.perform(get("/transfers/{id}", id)).andReturn();
  }

  private static void assertFixedServerError(MvcResult result, String id) throws Exception {
    String body = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus()).isEqualTo(500);
    assertThat(result.getResponse().getContentType()).contains("application/problem+json");
    assertThat((String) JsonPath.read(body, "$.detail")).isEqualTo(FIXED_DETAIL);
    assertThat((String) JsonPath.read(body, "$.instance")).isEqualTo("/transfers");
    assertThat(body)
      .doesNotContain(id)
      .doesNotContain("IT60")
      .doesNotContain(SEEDED_SOURCE_IBAN)
      .doesNotContain(VALID_DEST_IBAN)
      .doesNotContain(SHORT_DEST_IBAN)
      .doesNotContain("10.1234")
      .doesNotContain("Exception");
  }

  @Test
  void aStoredAmountWithAThirdOrFourthSignificantDecimalAnswers500() throws Exception {
    insertExternal(BAD_AMOUNT_ID, VALID_DEST_IBAN, "10.1234");

    assertFixedServerError(getTransfer(BAD_AMOUNT_ID), BAD_AMOUNT_ID);
  }

  @Test
  void aStoredInvalidDestIbanAnswers500AndNotA400() throws Exception {
    insertExternal(BAD_IBAN_ID, SHORT_DEST_IBAN, "10.50");

    assertFixedServerError(getTransfer(BAD_IBAN_ID), BAD_IBAN_ID);
  }

  @Test
  void aStoredAmountWithZerosBeyondTheSecondDecimalIsStillRead() throws Exception {
    insertExternal(BAD_AMOUNT_ID, VALID_DEST_IBAN, "10.5000");

    MvcResult result = getTransfer(BAD_AMOUNT_ID);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    Object amount = JsonPath.read(result.getResponse().getContentAsString(), "$.amount");
    assertThat(new BigDecimal(amount.toString())).isEqualByComparingTo("10.50");
  }

  @Test
  void unknownIdStillAnswers404() throws Exception {
    MvcResult result = getTransfer("no-such-transfer");

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.detail"))
      .isEqualTo("Transfer not found");
  }

  @Test
  void logsNeverContainIbansAmountsNorTheId(CapturedOutput output) throws Exception {
    insertExternal(BAD_AMOUNT_ID, VALID_DEST_IBAN, "10.1234");
    insertExternal(BAD_IBAN_ID, SHORT_DEST_IBAN, "10.50");

    getTransfer(BAD_AMOUNT_ID);
    getTransfer(BAD_IBAN_ID);

    assertThat(output.getAll())
      .doesNotContain(SEEDED_SOURCE_IBAN)
      .doesNotContain(VALID_DEST_IBAN)
      .doesNotContain(SHORT_DEST_IBAN)
      .doesNotContain("10.1234")
      .doesNotContain(BAD_AMOUNT_ID)
      .doesNotContain(BAD_IBAN_ID);
  }
}
