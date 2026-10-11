package com.codeweave.settings;

import com.codeweave.config.SecretCryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

/** Shared provider discovery for settings and console model routes. */
@Service
public class ModelCatalogService {
  private final ObjectMapper mapper;
  private final SecretCryptoService crypto;
  private final HttpClient http = HttpClient.newBuilder().build();
  private record CatalogEntry(ModelConfigRepository.ModelConfig config, Instant expiresAt, List<String> ids) {}
  private final Map<String, CatalogEntry> catalogCache = new HashMap<>();

  public ModelCatalogService(ObjectMapper mapper, SecretCryptoService crypto) {
    this.mapper = mapper;
    this.crypto = crypto;
  }

  public synchronized List<String> modelIds(ModelConfigRepository.ModelConfig config) {
    Instant now = Instant.now();
    catalogCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    String key = config.ownerId() + ":" + config.id();
    CatalogEntry cached = catalogCache.get(key);
    if (cached != null && cached.config().equals(config)) return cached.ids();
    List<String> ids = discoverModelIds(config);
    catalogCache.put(key, new CatalogEntry(config, Instant.now().plusSeconds(60), ids));
    return ids;
  }

  private List<String> discoverModelIds(ModelConfigRepository.ModelConfig config) {
    if (config.encryptedApiKey() == null || config.encryptedApiKey().isBlank()) return List.of();
    try {
      String key = crypto.decrypt(config.encryptedApiKey());
      URI endpoint = URI.create(config.endpoint().replaceAll("/+$", "") + "/models");
      HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(8))
        .header("Authorization", "Bearer " + key).header("Accept", "application/json").GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) return List.of();
      JsonNode root = mapper.readTree(response.body());
      JsonNode data = root.path("data");
      if (!data.isArray()) data = root.path("models");
      if (!data.isArray()) return List.of();
      List<String> ids = new ArrayList<>();
      for (JsonNode item : data) {
        String id = item.isTextual() ? item.asText().trim() : item.path("id").asText("").trim();
        if (!id.isBlank() && !ids.contains(id)) ids.add(id);
      }
      return ids;
    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return List.of(); }
    catch (Exception ignored) { return List.of(); }
  }

}
