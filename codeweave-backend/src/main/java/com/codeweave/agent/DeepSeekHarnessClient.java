package com.codeweave.agent;

import com.codeweave.config.DshProperties;
import com.codeweave.settings.ModelConfigRepository.ModelConfig;
import com.codeweave.settings.ModelCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal HTTP adapter to the standalone DeepSeek Harness web runtime. */
@Service
public class DeepSeekHarnessClient {
  private final DshProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final StringRedisTemplate redis;
  private final ModelCatalogService catalog;
  private final Set<String> following = ConcurrentHashMap.newKeySet();

  public DeepSeekHarnessClient(DshProperties properties, ObjectMapper mapper, StringRedisTemplate redis, ModelCatalogService catalog) {
    this.properties = properties; this.mapper = mapper;
    this.redis = redis;
    this.catalog = catalog;
    this.http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      // The authentication redirect carries the cookie needed by subsequent RPCs.
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
      .build();
  }

  public synchronized String createSession(String cwd, ModelConfig model, String apiKey) {
    String provider = configureModel(model, apiKey);
    rpc("settings/update", Map.of("ns", "agent-default-model", "patch", Map.of("provider", provider, "model", model.name())));
    Map<String, Object> request = new LinkedHashMap<>();
    if (cwd != null && !cwd.isBlank()) request.put("cwd", cwd);
    Map<String, Object> value = rpc("session/create", Map.of("request", request));
    Object id = value.get("sessionId");
    if (!(id instanceof String sessionId) || sessionId.isBlank()) {
      throw new DshException("DSH returned no session ID.");
    }
    selectModel(sessionId, provider, model.name());
    startFollow(sessionId);
    return sessionId;
  }

  public synchronized void prompt(String sessionId, String text, java.util.List<String> receiptIds, java.util.List<Map<String, Object>> images, ModelConfig model, String apiKey) {
    String provider = configureModel(model, apiKey);
    selectModel(sessionId, provider, model.name());
    startFollow(sessionId);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("requestId", UUID.randomUUID().toString()); request.put("sessionId", sessionId); request.put("mode", "queue");
    var content = new java.util.ArrayList<Map<String, Object>>();
    if (text != null && !text.isBlank()) content.add(Map.of("type", "text", "text", text));
    if (receiptIds != null) receiptIds.stream().filter(id -> id != null && !id.isBlank()).forEach(id -> content.add(Map.of("type", "file", "receiptId", id)));
    if (images != null) images.forEach(image -> content.add(Map.of("type", "image", "mediaType", image.get("mediaType"), "data", image.get("data"), "name", image.getOrDefault("name", "image"))));
    request.put("content", content);
    request.put("clientTimeZone", "Asia/Shanghai");
    Map<String, Object> result = rpc("session/prompt", Map.of("request", request));
    if (!Boolean.TRUE.equals(result.get("accepted"))) throw new DshException("DSH did not accept the message.");
  }

  public synchronized void selectModelForSession(String sessionId, ModelConfig model, String apiKey) {
    String provider = configureModel(model, apiKey);
    selectModel(sessionId, provider, model.name());
  }

  public synchronized void cancel(String sessionId) {
    rpc("session/cancel", Map.of("request", Map.of("sessionId", sessionId)));
  }

  public void checkConnection() {
    try { cookie(); }
    catch (DshException error) { throw error; }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new DshException("DSH connection check was interrupted."); }
    catch (Exception error) { throw new DshException("Cannot reach the DSH console. Check the runtime address and authentication token."); }
  }

  public synchronized String syncConsoleModel(ModelConfig model, String apiKey) {
    return configureModel(model, apiKey);
  }

  public String consoleUrl() {
    return DshConsoleAddress.authenticatedUrl(properties);
  }

  public String consoleUrl(String workspaceId, java.util.List<String> providers) {
    String base = consoleUrl();
    base += (base.contains("?") ? "&" : "?") + "codeweaveProviders=" + java.net.URLEncoder.encode(String.join(",", providers), java.nio.charset.StandardCharsets.UTF_8);
    if (workspaceId == null || workspaceId.isBlank()) return base;
    return base + (base.contains("?") ? "&" : "?")
      + "codeweaveWorkspaceId=" + java.net.URLEncoder.encode(workspaceId, java.nio.charset.StandardCharsets.UTF_8);
  }

  public synchronized String ensureWorkspace(String path, String title) {
    if (path == null || path.isBlank()) return "";
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("path", path.trim());
    Map<String, Object> value = rpc("workspace/create", Map.of("request", request));
    Object workspace = value.get("workspace");
    if (!(workspace instanceof Map<?, ?> row)) throw new DshException("DSH returned no workspace.");
    Object workspaceId = row.get("workspaceId");
    if (!(workspaceId instanceof String id) || id.isBlank()) throw new DshException("DSH returned no workspace ID.");
    if (title != null && !title.isBlank()) {
      try { rpc("workspace/rename", Map.of("request", Map.of("workspaceId", id, "title", title.trim()))); }
      catch (DshException ignored) { }
    }
    return id;
  }

  public synchronized Map<String, Object> uploadFile(String sessionId, byte[] bytes, String name) {
    try {
      String cookie = cookie();
      String query = "?sessionId=" + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8)
        + (name == null || name.isBlank() ? "" : "&name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedBaseUrl() + "/api/session/uploadFileBinary" + query))
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("content-type", "application/octet-stream").header("cookie", cookie)
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new DshException("DSH file upload returned HTTP " + response.statusCode() + ".");
      Map<String, Object> envelope = mapper.readValue(response.body(), new TypeReference<>() {});
      if (!Boolean.TRUE.equals(envelope.get("ok"))) throw new DshException("DSH rejected the file upload: " + String.valueOf(envelope.getOrDefault("error", "unknown error")));
      Object value = envelope.get("value");
      return value instanceof Map<?, ?> map ? mapper.convertValue(map, new TypeReference<Map<String, Object>>() {}) : Map.of();
    } catch (DshException ex) { throw ex; }
    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new DshException("DSH file upload was interrupted."); }
    catch (Exception ex) { throw new DshException("Cannot upload the file to DeepSeek Harness."); }
  }

  private String configureModel(ModelConfig model, String apiKey) {
    // A saved connection owns a stable route; selecting a model must not create another provider.
    String identity = UUID.nameUUIDFromBytes((model.ownerId() + ":" + model.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
    String provider = "codeweave-" + identity;
    String credential = "CODEWEAVE_MODEL_" + identity;
    Set<String> modelIds = new java.util.LinkedHashSet<>();
    modelIds.add(model.name());
    modelIds.addAll(catalog.modelIds(model));
    String displayName = "CodeWeave · " + model.name() + " · " + model.id().substring(0, Math.min(8, model.id().length()));
    rpc("credentials/set", Map.of("ref", credential, "value", apiKey));
    rpc("settings/update", Map.of("ns", "llm-pi-ai", "patch", Map.of("providers", Map.of(provider, Map.of(
      "displayName", displayName,
      "apiKeyEnv", credential,
      "api", "openai-completions",
      "baseURL", model.endpoint().trim().replaceAll("/+$", ""),
      "defaultContextWindow", 65536,
      "defaultMaxTokens", 8192,
      "defaultInput", java.util.List.of("text", "image"),
      "models", modelIds.stream().map(id -> Map.of(
        "id", id,
        "name", id,
        "contextWindow", 65536,
        "maxTokens", 8192,
        "input", java.util.List.of("text", "image")
      )).toList()
    )))));
    return provider;
  }

  private void selectModel(String sessionId, String provider, String model) {
    rpc("session/selectModel", Map.of("request", Map.of("sessionId", sessionId, "provider", provider, "model", model)));
  }

  private void startFollow(String sessionId) {
    if (!following.add(sessionId)) return;
    try {
      String cookie = cookie();
      String streamId = UUID.randomUUID().toString();
      String payload = mapper.writeValueAsString(Map.of("type", "open", "streamId", streamId, "endpoint", "session/follow", "payload", Map.of("args", Map.of("request", Map.of("address", Map.of("kind", "session", "sessionId", sessionId), "maxMessages", 100, "assistantStream", true)))));
      WebSocket.Listener listener = new WebSocket.Listener() {
        private final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.sendText(payload, true); socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
          buffer.append(data);
          if (last) {
            try {
              Map<String, Object> frame = mapper.readValue(buffer.toString(), new TypeReference<>() {});
              if (streamId.equals(frame.get("streamId")) && "item".equals(frame.get("type"))) {
                redis.opsForStream().add("cw:agent:events", Map.of("sessionId", sessionId, "payload", mapper.writeValueAsString(frame.get("value")), "createdAt", String.valueOf(System.currentTimeMillis())));
              }
            } catch (Exception ignored) { }
            buffer.setLength(0);
          }
          socket.request(1);
          return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) { following.remove(sessionId); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { following.remove(sessionId); }
      };
      http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Cookie", cookie).buildAsync(URI.create(wsUrl() + "/api/remote.mux"), listener)
        .whenComplete((socket, error) -> { if (error != null) following.remove(sessionId); });
    } catch (Exception ex) { following.remove(sessionId); }
  }

  private Map<String, Object> rpc(String method, Map<String, Object> args) {
    try {
      String cookie = cookie();
      String body = mapper.writeValueAsString(Map.of("type", "client-request", "rpcId", UUID.randomUUID().toString(), "method", method, "payload", Map.of("args", args)));
      HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedBaseUrl() + "/api/" + method)).timeout(Duration.ofSeconds(properties.getTimeoutSeconds())).header("content-type", "application/json").header("cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new DshException("DSH returned HTTP " + response.statusCode() + ".");
      Map<String, Object> envelope = mapper.readValue(response.body(), new TypeReference<>() {});
      Object result = envelope.get("result");
      if (!(result instanceof Map<?, ?> resultMap) || !Boolean.TRUE.equals(resultMap.get("ok"))) {
        String detail = "";
        if (result instanceof Map<?, ?> resultMap) {
          Object error = resultMap.get("error");
          if (error instanceof Map<?, ?> errorMap) {
            Object message = errorMap.get("message");
            detail = message == null ? "" : String.valueOf(message);
          }
          else if (error != null) detail = String.valueOf(error);
        }
        throw new DshException("DSH rejected " + method + (detail.isBlank() ? ". Check the runtime configuration and API compatibility." : ": " + detail));
      }
      Object value = resultMap.get("value"); return value instanceof Map<?, ?> map ? mapper.convertValue(map, new TypeReference<Map<String, Object>>() {}) : Map.of();
    } catch (DshException ex) { throw ex; }
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new DshException("DSH request was interrupted.");
    } catch (Exception ex) {
      throw new DshException("Cannot communicate with DeepSeek Harness. Check its address and runtime status.");
    }
  }

  private String cookie() throws Exception {
    String token = DshConsoleAddress.authToken(properties);
    String authUrl = normalizedBaseUrl() + "/" + (token == null || token.isBlank() ? "" : "?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl)).timeout(Duration.ofSeconds(properties.getTimeoutSeconds())).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401 || response.statusCode() == 403) {
      throw new DshException("DSH authentication failed. Update codeweave.agent.dsh.auth-token using the current DSH launch URL.");
    }
    if (response.statusCode() != 303 && response.statusCode() / 100 != 2) {
      throw new DshException("DSH authentication returned HTTP " + response.statusCode() + ".");
    }
    return response.headers().allValues("set-cookie").stream()
      .map(value -> value.split(";", 2)[0]).filter(value -> value.startsWith("dsh-auth-"))
      .findFirst().orElseThrow(() -> new DshException("DSH returned no authentication cookie. Check the runtime token."));
  }

  public static final class DshException extends RuntimeException {
    public DshException(String message) { super(message); }
  }

  private String normalizedBaseUrl() { URI base = URI.create(properties.getBaseUrl()); return URI.create(base.getScheme() + "://" + base.getRawAuthority() + (base.getRawPath() == null ? "" : base.getRawPath())).toString().replaceAll("/+$", ""); }
  private String wsUrl() { return normalizedBaseUrl().replaceFirst("^http", "ws"); }
}
