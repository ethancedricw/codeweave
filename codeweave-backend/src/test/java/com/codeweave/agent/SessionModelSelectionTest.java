package com.codeweave.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.codeweave.config.SecretCryptoService;
import com.codeweave.settings.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionModelSelectionTest {
  @Test
  void accountWithoutModelCannotOpenPersistedDshProviders() {
    try (var auth = mockStatic(StpUtil.class)) {
      auth.when(StpUtil::getLoginIdAsString).thenReturn("new-owner");
      var dsh = mock(DeepSeekHarnessClient.class);
      var repository = mock(ModelConfigRepository.class);
      when(repository.findAll("new-owner")).thenReturn(List.of());
      var controller = new AgentController(dsh, mock(StringRedisTemplate.class), new ObjectMapper(), repository, mock(SecretCryptoService.class));

      assertThrows(ResponseStatusException.class, () -> controller.dshConsole(Map.of()));
      verifyNoInteractions(dsh);
      verify(dsh, never()).syncConsoleModel(any(), anyString());
    }
  }

  @Test
  void selectedProviderModelSurvivesSessionStorageAndIsUsedByNextPrompt() throws Exception {
    try (var auth = mockStatic(StpUtil.class)) {
      auth.when(StpUtil::getLoginIdAsString).thenReturn("owner");
      var redis = mock(StringRedisTemplate.class);
      var hash = mock(HashOperations.class);
      var stream = mock(StreamOperations.class);
      when(redis.opsForHash()).thenReturn(hash);
      when(redis.opsForStream()).thenReturn(stream);
      var dsh = mock(DeepSeekHarnessClient.class);
      var repository = mock(ModelConfigRepository.class);
      var crypto = mock(SecretCryptoService.class);
      var mapper = new ObjectMapper();
      var saved = new AtomicReference<>("{\"id\":\"session\",\"ownerId\":\"owner\",\"modelId\":\"config\"}");
      when(hash.get("cw:workspace:sessions", "session")).thenAnswer(invocation -> saved.get());
      doAnswer(invocation -> { saved.set(invocation.getArgument(2)); return null; })
        .when(hash).put(eq("cw:workspace:sessions"), eq("session"), anyString());
      var config = new ModelConfigRepository.ModelConfig("config", "owner", "fallback", "https://provider.example/v1", "cipher", true, Instant.EPOCH);
      when(repository.find("owner", "config")).thenReturn(Optional.of(config));
      when(crypto.decrypt("cipher")).thenReturn("test-key");
      var controller = new AgentController(dsh, redis, mapper, repository, crypto);
      controller.selectModel("session", Map.of("modelId", "config::discovered"));
      assertEquals("discovered", mapper.readTree(saved.get()).path("modelName").asText());
      controller.prompt("session", Map.of("text", "Hello"));
      verify(dsh).prompt(eq("session"), eq("Hello"), eq(List.of()), eq(List.of()), argThat(model ->
        model.id().equals("config") && model.modelId().equals("discovered") && model.endpoint().equals(config.endpoint())), eq("test-key"));
      verify(repository, never()).save(any());
    }
  }
}
