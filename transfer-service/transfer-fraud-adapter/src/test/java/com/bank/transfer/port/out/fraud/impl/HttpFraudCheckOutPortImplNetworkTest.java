package com.bank.transfer.port.out.fraud.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.domain.FraudStatusEnum;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.web.client.RestClient;

/** Real sockets: a JDK HttpServer on an ephemeral port stands in for the Fraud service. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class HttpFraudCheckOutPortImplNetworkTest {

  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String start(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", handler);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static HttpFraudCheckOutPortImpl portFor(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    RestClient client = FraudRestClientFactory.create(baseUrl, connectTimeout, readTimeout);
    return new HttpFraudCheckOutPortImpl(client);
  }

  private static FraudCheckResultDto check(HttpFraudCheckOutPortImpl port) {
    return port.checkFraud(ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR");
  }

  private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Test
  void n1_slowButInTimeAllowedResponseIsReadThroughTheRealFactory() throws Exception {
    List<String> seen = new CopyOnWriteArrayList<>();
    String baseUrl = start(exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      reply(exchange, 200, "application/json", "{\"transferId\":\"" + ID + "\",\"status\":\"ALLOWED\"}");
    });

    FraudCheckResultDto result = check(portFor(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(3)));

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.ALLOWED);
    assertThat(result.reasons()).isEmpty();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0)).startsWith("POST /fraud-check ").contains("\"transferId\":\"" + ID + "\"")
      .contains("\"destinationIban\":\"" + IBAN_2 + "\"").contains("\"amount\":100.25");
  }

  @Test
  void n1_rejectedResponseWithReasonsIsReadThroughTheRealFactory() throws Exception {
    String baseUrl = start(exchange -> {
      exchange.getRequestBody().readAllBytes();
      reply(exchange, 200, "application/json",
        "{\"transferId\":\"" + ID + "\",\"status\":\"REJECTED\",\"reasons\":[\"a\",\"b\"]}");
    });

    FraudCheckResultDto result = check(portFor(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(3)));

    assertThat(result.decision()).isEqualTo(FraudStatusEnum.REJECTED);
    assertThat(result.reasons()).containsExactly("a", "b");
  }

  @Test
  void n1_aRedirectIsNotFollowedAndMeansUnavailable() throws Exception {
    String baseUrl = start(exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().add("Location", "/elsewhere");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    assertThatThrownBy(() -> check(portFor(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(3))))
      .isInstanceOf(FraudCheckUnavailableException.class);
  }

  @Test
  void n2_aResponseSlowerThanTheReadTimeoutIsUnavailableAndWeDoNotWaitForIt() throws Exception {
    String baseUrl = start(exchange -> {
      exchange.getRequestBody().readAllBytes();
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      reply(exchange, 200, "application/json", "{\"transferId\":\"" + ID + "\",\"status\":\"ALLOWED\"}");
    });
    HttpFraudCheckOutPortImpl port = portFor(baseUrl, Duration.ofSeconds(1), Duration.ofMillis(200));

    long start = System.nanoTime();
    assertThatThrownBy(() -> check(port)).isInstanceOf(FraudCheckUnavailableException.class);
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(elapsedMillis).isLessThan(900);
  }

  @Test
  void n3_aRefusedConnectionIsUnavailable() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    HttpFraudCheckOutPortImpl port =
      portFor("http://127.0.0.1:" + closedPort, Duration.ofSeconds(1), Duration.ofSeconds(3));

    assertThatThrownBy(() -> check(port)).isInstanceOf(FraudCheckUnavailableException.class);
  }

  @Test
  void n2n3_readTimeoutAndRefusedConnectionLogDifferentCauseClassesAndNeverTheSpringWrapper(CapturedOutput output)
    throws Exception {
    String slowBaseUrl = start(exchange -> {
      exchange.getRequestBody().readAllBytes();
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      reply(exchange, 200, "application/json", "{\"transferId\":\"" + ID + "\",\"status\":\"ALLOWED\"}");
    });
    HttpFraudCheckOutPortImpl slowPort = portFor(slowBaseUrl, Duration.ofSeconds(1), Duration.ofMillis(200));
    assertThatThrownBy(() -> check(slowPort)).isInstanceOf(FraudCheckUnavailableException.class);
    String afterTimeout = output.getAll();

    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    HttpFraudCheckOutPortImpl refusedPort =
      portFor("http://127.0.0.1:" + closedPort, Duration.ofSeconds(1), Duration.ofSeconds(3));
    assertThatThrownBy(() -> check(refusedPort)).isInstanceOf(FraudCheckUnavailableException.class);
    String afterRefused = output.getAll().substring(afterTimeout.length());

    String timeoutName = causeNameOf(afterTimeout);
    String refusedName = causeNameOf(afterRefused);
    assertThat(timeoutName).isNotEqualTo("ResourceAccessException");
    assertThat(refusedName).isNotEqualTo("ResourceAccessException");
    assertThat(timeoutName).isNotEqualTo(refusedName);
    assertThat(afterTimeout + afterRefused).doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("100.25");
  }

  /** The word that follows IO_ERROR in the WARN line (ignoring punctuation), i.e. the logged class name. */
  private static String causeNameOf(String log) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("IO_ERROR\\W*(\\w+)").matcher(log);
    assertThat(m.find()).as("IO_ERROR warn line in: " + log).isTrue();
    return m.group(1);
  }

  @ParameterizedTest(name = "baseUrl [{0}]")
  @CsvSource(value = {"''", "'   '"})
  void n4_anEmptyBaseUrlIsRefused(String baseUrl) {
    assertThatThrownBy(() -> FraudRestClientFactory.create(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(3)))
      .isInstanceOf(IllegalArgumentException.class)
      .satisfies(e -> assertThat(e.getMessage()).isNotBlank());
  }

  @ParameterizedTest(name = "connect {0} ms, read {1} ms")
  @CsvSource({"0, 3000", "-1, 3000", "1000, 0", "1000, -1"})
  void n4_zeroOrNegativeDurationsAreRefused(long connectMillis, long readMillis) {
    assertThatThrownBy(() -> FraudRestClientFactory.create("http://127.0.0.1:1",
      Duration.ofMillis(connectMillis), Duration.ofMillis(readMillis)))
      .isInstanceOf(IllegalArgumentException.class)
      .satisfies(e -> assertThat(e.getMessage()).isNotBlank());
  }
}
