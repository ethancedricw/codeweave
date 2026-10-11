package com.codeweave.agent;

import com.codeweave.config.DshProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DshConsoleAddress {
  private DshConsoleAddress() {}

  static String authenticatedUrl(DshProperties properties) {
    URI base = URI.create(properties.getBaseUrl());
    URI browser = browserUri(properties, base);
    validate(browser);
    String path = browser.getRawPath() == null ? "" : browser.getRawPath();
    String url = browser.getScheme() + "://" + browser.getRawAuthority() + path.replaceAll("/+$", "") + "/";
    String query = withToken(browser.getRawQuery(), configuredToken(properties, base, browser));
    return query.isBlank() ? url : url + "?" + query;
  }

  static String authToken(DshProperties properties) {
    URI base = URI.create(properties.getBaseUrl());
    return configuredToken(properties, base, browserUri(properties, base));
  }

  private static URI browserUri(DshProperties properties, URI base) {
    String configured = properties.getConsoleUrl();
    return configured == null || configured.isBlank() ? base : URI.create(configured);
  }

  private static void validate(URI browser) {
    if (!("http".equalsIgnoreCase(browser.getScheme()) || "https".equalsIgnoreCase(browser.getScheme()))
        || browser.getHost() == null || browser.getRawUserInfo() != null) {
      throw new DeepSeekHarnessClient.DshException("Configure a valid HTTP or HTTPS DSH console URL.");
    }
  }

  private static String configuredToken(DshProperties properties, URI base, URI browser) {
    String token = properties.getAuthToken();
    if (token != null && !token.isBlank()) return token;
    token = queryValue(browser.getRawQuery(), "token");
    if (token != null && !token.isBlank()) return token;
    return queryValue(base.getRawQuery(), "token");
  }

  private static String queryValue(String rawQuery, String key) {
    if (rawQuery == null || rawQuery.isBlank()) return "";
    for (String part : rawQuery.split("&")) {
      int separator = part.indexOf('=');
      String rawKey = separator < 0 ? part : part.substring(0, separator);
      if (key.equals(URLDecoder.decode(rawKey, StandardCharsets.UTF_8))) {
        return separator < 0 ? "" : URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private static String withToken(String rawQuery, String token) {
    List<String> parts = new ArrayList<>();
    if (rawQuery != null && !rawQuery.isBlank()) {
      for (String part : rawQuery.split("&")) {
        int separator = part.indexOf('=');
        String rawKey = separator < 0 ? part : part.substring(0, separator);
        if (!"token".equals(URLDecoder.decode(rawKey, StandardCharsets.UTF_8))) parts.add(part);
      }
    }
    if (token != null && !token.isBlank()) parts.add("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    return String.join("&", parts);
  }
}
