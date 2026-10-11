package com.codeweave.settings;

import cn.dev33.satoken.stp.StpUtil;
import com.codeweave.config.SecretCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCatalogTest {
  @Test
  void catalogPreservesConfiguredFallbackAndCredentialRouteWhenDiscoveryIsUnavailable() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/available/models", exchange -> {
      assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = "{\"data\":[{\"id\":\"remote-model\"},{\"id\":\"remote-model\"}]}".getBytes();
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/unsupported/models", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
    server.start();
    try (var auth = mockStatic(StpUtil.class)) {
      auth.when(StpUtil::getLoginIdAsString).thenReturn("owner");
      var repository = mock(ModelConfigRepository.class);
      var crypto = mock(SecretCryptoService.class);
      when(crypto.decrypt("cipher")).thenReturn("test-key");
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      when(repository.findAll("owner")).thenReturn(List.of(
        new ModelConfigRepository.ModelConfig("a", "owner", "manual-default", base + "/available", "cipher", true, Instant.EPOCH),
        new ModelConfigRepository.ModelConfig("b", "owner", "remote-model", base + "/unsupported", "cipher", false, Instant.EPOCH),
        new ModelConfigRepository.ModelConfig("c", "owner", "no-key", base, "", false, Instant.EPOCH)));
      var controller = new SettingsController(null, new ObjectMapper(), repository, crypto, null, null, null, null, new ModelCatalogService(new ObjectMapper(), crypto));
      var result = (List<Map<String, Object>>) controller.modelCatalog().get("items");
      assertEquals(List.of("a::manual-default", "a::remote-model", "b::remote-model"), result.stream().map(item -> item.get("id")).toList());
      assertEquals(true, result.get(0).get("defaultModel"));
      assertEquals(false, result.get(0).get("discovered"));
      assertEquals(true, result.get(1).get("discovered"));
      assertEquals("b", result.get(2).get("configId"));
      assertEquals(false, result.get(2).get("discovered"));
    } finally { server.stop(0); }
  }
}
