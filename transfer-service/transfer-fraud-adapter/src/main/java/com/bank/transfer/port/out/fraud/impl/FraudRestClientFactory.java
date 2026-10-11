package com.bank.transfer.port.out.fraud.impl;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class FraudRestClientFactory {

  private FraudRestClientFactory() {
  }

  public static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Fraud base URL must not be blank");
    }
    requirePositive(connectTimeout, "Fraud connect timeout must be positive");
    requirePositive(readTimeout, "Fraud read timeout must be positive");

    HttpClient httpClient = HttpClient.newBuilder()
                              .connectTimeout(connectTimeout)
                              .version(HttpClient.Version.HTTP_1_1)
                              .followRedirects(HttpClient.Redirect.NEVER)
                              .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);

    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  private static void requirePositive(Duration duration, String message) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(message);
    }
  }
}
