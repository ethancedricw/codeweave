package com.codeweave.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.codeweave.config.SecretCryptoService;
import com.codeweave.settings.ModelConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
  private static final String SESSION_HASH = "cw:workspace:sessions";
  private static final String ACTIVITY_STREAM = "cw:workspace:activity";
  private final DeepSeekHarnessClient dsh;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final ModelConfigRepository models;
  private final SecretCryptoService crypto;
  public AgentController(DeepSeekHarnessClient dsh, StringRedisTemplate redis, ObjectMapper mapper, ModelConfigRepository models, SecretCryptoService crypto) {
    this.dsh = dsh; this.redis = redis; this.mapper = mapper; this.models = models; this.crypto = crypto;
  }

  @ExceptionHandler(DeepSeekHarnessClient.DshException.class)
  public ResponseEntity<Map<String, String>> runtimeUnavailable(DeepSeekHarnessClient.DshException error) {
    return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
  }

  @PostMapping("/dsh-console")
  public ResponseEntity<Map<String, Object>> dshConsole(@RequestBody(required = false) Map<String, Object> request) {
    // The native console controls the whole runtime, so a project-scoped login cannot authorize it.
    StpUtil.checkLogin();
    dsh.checkConnection();
    var configured = models.findAll(StpUtil.getLoginIdAsString()).stream()
      .filter(model -> model.encryptedApiKey() != null && !model.encryptedApiKey().isBlank()).toList();
    List<String> providers = configured.stream().map(model -> dsh.syncConsoleModel(model, modelKey(model))).toList();
    String projectPath = request == null ? "" : String.valueOf(request.getOrDefault("serverPath", request.getOrDefault("projectPath", ""))).trim();
    String projectName = request == null ? "" : String.valueOf(request.getOrDefault("projectName", "")).trim();
    String projectId = request == null ? "" : String.valueOf(request.getOrDefault("projectId", "")).trim();
    String existingWorkspaceId = "";
    if (!projectId.isBlank()) {
      Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
      if (raw != null) {
        try {
          Map<String, Object> project = mapper.readValue(String.valueOf(raw), new TypeReference<>() {});
          projectPath = String.valueOf(project.getOrDefault("serverPath", "")).trim();
          if (projectName.isBlank()) projectName = String.valueOf(project.getOrDefault("name", ""));
          existingWorkspaceId = String.valueOf(project.getOrDefault("dshWorkspaceId", "")).trim();
        } catch (Exception ignored) { }
      }
    }
    String workspaceId = existingWorkspaceId.isBlank() && !projectPath.isBlank() ? dsh.ensureWorkspace(projectPath, projectName) : existingWorkspaceId;
    if (!projectId.isBlank() && !workspaceId.isBlank()) {
      Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
      if (raw != null) {
        try {
          Map<String, Object> project = mapper.readValue(String.valueOf(raw), new TypeReference<>() {});
          project.put("dshWorkspaceId", workspaceId);
          redis.opsForHash().put("cw:workspace:projects", projectId, mapper.writeValueAsString(project));
        } catch (Exception ignored) { }
      }
    }
    return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
      .body(Map.of("url", dsh.consoleUrl(workspaceId, providers), "workspaceId", workspaceId));
  }

  @PostMapping("/sessions")
  public Map<String, Object> createSession(@RequestBody(required = false) Map<String, Object> request) {
    StpUtil.checkLogin();
    var model = currentModel(request);
    String cwd = request == null ? "" : String.valueOf(request.getOrDefault("cwd", ""));
    if (cwd.isBlank() && request != null) cwd = projectWorkspace(String.valueOf(request.getOrDefault("projectId", "")));
    String sessionId = dsh.createSession(cwd, model, modelKey(model));
    Map<String, Object> session = new LinkedHashMap<>();
    session.put("id", sessionId);
    session.put("ownerId", StpUtil.getLoginIdAsString());
    session.put("modelId", model.id());
    session.put("modelName", model.name());
    session.put("dshSessionId", sessionId);
    session.put("title", value(request, "title", "新建协作任务"));
    session.put("projectId", value(request, "projectId", ""));
    session.put("project", value(request, "projectName", "未选择项目"));
    session.put("state", "运行中");
    session.put("detail", "等待 Agent 更新");
    session.put("who", "A");
    session.put("tone", "cyan");
    session.put("runtime", "deepseek-harness");
    session.put("createdAt", Instant.now().toString());
    session.put("updatedAt", Instant.now().toString());
    saveSession(session);
    recordActivity("agent.started", "Agent 开始处理任务", String.valueOf(session.get("title")), "mint");
    return Map.of("sessionId", sessionId, "runtime", "deepseek-harness", "session", session);
  }

  @PostMapping("/sessions/{sessionId}/messages")
  public Map<String, Object> prompt(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin(); String text = String.valueOf(request.getOrDefault("text", ""));
    if (text.isBlank() && !(request.get("receiptIds") instanceof List<?> values && !values.isEmpty())) throw new IllegalArgumentException("消息不能为空");
    Map<String, Object> session = loadSession(sessionId);
    if (session.containsKey("ownerId") && !StpUtil.getLoginIdAsString().equals(session.get("ownerId"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session belongs to another user.");
    }
    var model = sessionModel(session);
    List<String> receiptIds = request.get("receiptIds") instanceof List<?> values
      ? values.stream().map(String::valueOf).toList() : List.of();
    List<Map<String, Object>> images = request.get("images") instanceof List<?> values
      ? values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList() : List.of();
    dsh.prompt(sessionId, text, receiptIds, images, model, modelKey(model));
    if (!session.isEmpty()) {
      session.put("state", "运行中");
      session.put("detail", "正在处理最新指令");
      session.put("latestRole", "user");
      session.put("latestActor", "你");
      session.put("latestAction", "发送了指令");
      session.put("latestMessage", shorten(text));
      session.put("latestAt", Instant.now().toString());
      if ("新建协作任务".equals(session.get("title"))) session.put("title", shorten(text, 36));
      session.put("updatedAt", Instant.now().toString());
      saveSession(session);
      recordActivity("agent.prompted", "提交了新的 Agent 指令", String.valueOf(session.getOrDefault("title", sessionId)), "cyan");
    }
    return Map.of("accepted", true, "sessionId", sessionId);
  }

  @PutMapping("/sessions/{sessionId}/model")
  public Map<String, Object> selectModel(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    Map<String, Object> session = loadSession(sessionId);
    if (session.isEmpty() || !StpUtil.getLoginIdAsString().equals(session.get("ownerId"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    String modelId = String.valueOf(request.getOrDefault("modelId", "")).trim();
    String requestedName = String.valueOf(request.getOrDefault("modelName", "")).trim();
    var model = resolveModelSelection(modelId, requestedName);
    dsh.selectModelForSession(sessionId, model, modelKey(model));
    session.put("modelId", model.id()); session.put("modelName", model.name()); session.put("updatedAt", Instant.now().toString()); saveSession(session);
    return Map.of("modelId", model.id() + "::" + model.name(), "name", model.name());
  }

  @PostMapping("/sessions/{sessionId}/cancel")
  public Map<String, Object> cancel(@PathVariable String sessionId) {
    StpUtil.checkLogin();
    Map<String, Object> session = loadSession(sessionId);
    if (session.isEmpty() || !StpUtil.getLoginIdAsString().equals(session.get("ownerId"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    dsh.cancel(sessionId);
    session.put("state", "已中断"); session.put("detail", "Agent 已中断"); session.put("updatedAt", Instant.now().toString()); saveSession(session);
    return Map.of("accepted", true, "sessionId", sessionId);
  }

  @PostMapping(value = "/sessions/{sessionId}/attachments", consumes = "multipart/form-data")
  public Map<String, Object> uploadAttachment(@PathVariable String sessionId, @RequestPart("file") MultipartFile file) {
    StpUtil.checkLogin();
    Map<String, Object> session = loadSession(sessionId);
    if (session.isEmpty() || !StpUtil.getLoginIdAsString().equals(session.get("ownerId"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    }
    if (file.isEmpty() || file.getSize() > 50 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be between 1 byte and 50 MB.");
    try {
      Map<String, Object> uploaded = dsh.uploadFile(sessionId, file.getBytes(), file.getOriginalFilename());
      Object receiptId = uploaded.get("receiptId");
      if (receiptId == null || String.valueOf(receiptId).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek Harness returned no upload receipt.");
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("receiptId", String.valueOf(receiptId));
      result.put("file", uploaded.getOrDefault("file", Map.of("name", file.getOriginalFilename(), "bytes", file.getSize())));
      if (file.getContentType() != null && file.getContentType().toLowerCase().startsWith("image/") && file.getSize() <= 10 * 1024 * 1024) {
        result.put("image", Map.of("data", java.util.Base64.getEncoder().encodeToString(file.getBytes()), "mediaType", file.getContentType(), "name", file.getOriginalFilename() == null ? "image" : file.getOriginalFilename()));
      }
      return result;
    } catch (java.io.IOException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file could not be read.", error); }
  }

  @GetMapping("/sessions/{sessionId}/events")
  public Map<String, Object> events(@PathVariable String sessionId) {
    StpUtil.checkLogin();
    var items = redis.opsForStream().range("cw:agent:events", Range.unbounded()).stream()
      .filter(record -> sessionId.equals(String.valueOf(record.getValue().get("sessionId"))))
      .map(record -> {
        Map<String, Object> item = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> item.put(String.valueOf(key), value));
        item.put("eventId", record.getId().getValue());
        syncSessionState(sessionId, String.valueOf(item.get("payload")));
        return item;
      }).toList();
    return Map.of("items", items);
  }

  private String value(Map<String, Object> request, String key, String fallback) {
    if (request == null) return fallback;
    String value = String.valueOf(request.getOrDefault(key, fallback)).trim();
    return value.isBlank() ? fallback : value;
  }

  private ModelConfigRepository.ModelConfig currentModel(Map<String, Object> request) {
    var available = models.findAll(StpUtil.getLoginIdAsString());
    String requested = request == null ? "" : String.valueOf(request.getOrDefault("modelId", ""));
    String requestedName = request == null ? "" : String.valueOf(request.getOrDefault("modelName", "")).trim();
    if (!requested.isBlank()) return resolveModelSelection(requested, requestedName);
    return available.stream().filter(ModelConfigRepository.ModelConfig::defaultModel).findFirst()
      .or(() -> available.stream().findFirst())
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save a model configuration in Settings before starting a conversation."));
  }

  private ModelConfigRepository.ModelConfig sessionModel(Map<String, Object> session) {
    String id = String.valueOf(session.getOrDefault("modelId", ""));
    String name = String.valueOf(session.getOrDefault("modelName", "")).trim();
    if (!id.isBlank()) {
      try { return resolveModelSelection(id, name); } catch (ResponseStatusException ignored) { }
    }
    return currentModel(null);
  }

  private ModelConfigRepository.ModelConfig resolveModelSelection(String selection, String requestedName) {
    String configId = selection;
    String selectedName = requestedName;
    int separator = selection.indexOf("::");
    if (separator > 0) {
      configId = selection.substring(0, separator);
      if (selectedName.isBlank()) selectedName = selection.substring(separator + 2);
    }
    var base = models.find(StpUtil.getLoginIdAsString(), configId)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model configuration not found."));
    if (selectedName.isBlank() || selectedName.equals(base.name())) return base;
    return new ModelConfigRepository.ModelConfig(base.id(), base.ownerId(), selectedName, base.endpoint(), base.encryptedApiKey(), base.defaultModel(), base.updatedAt());
  }

  private String modelKey(ModelConfigRepository.ModelConfig model) {
    if (model.encryptedApiKey() == null || model.encryptedApiKey().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save an API key for the selected model in Settings.");
    }
    try { return crypto.decrypt(model.encryptedApiKey()); }
    catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The saved API key cannot be decrypted. Enter and save it again in Settings.");
    }
  }

  private void saveSession(Map<String, Object> session) {
    try { redis.opsForHash().put(SESSION_HASH, String.valueOf(session.get("id")), mapper.writeValueAsString(session)); }
    catch (Exception error) { throw new IllegalStateException("会话保存失败", error); }
  }

  private Map<String, Object> loadSession(String id) {
    Object raw = redis.opsForHash().get(SESSION_HASH, id);
    if (raw == null) return new LinkedHashMap<>();
    try { return mapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {}); }
    catch (Exception ignored) { return new LinkedHashMap<>(); }
  }

  private String projectWorkspace(String projectId) {
    if (projectId == null || projectId.isBlank()) return "";
    Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (raw == null) return "";
    try {
      Map<String, Object> project = mapper.readValue(String.valueOf(raw), new TypeReference<>() {});
      return String.valueOf(project.getOrDefault("serverPath", ""));
    } catch (Exception ignored) { return ""; }
  }

  private void recordActivity(String type, String action, String target, String tone) {
    redis.opsForStream().add(ACTIVITY_STREAM, Map.of("type", type, "action", action, "target", target, "tone", tone, "actor", "Lin", "createdAt", Instant.now().toString()));
  }

  private void syncSessionState(String id, String payload) {
    try {
      var root = mapper.readTree(payload);
      Map<String, Object> session = loadSession(id);
      if (session.isEmpty()) return;
      if (applyEvent(root, session)) {
        session.put("updatedAt", Instant.now().toString());
        session.put("latestAt", session.get("updatedAt"));
        saveSession(session);
      }
    } catch (Exception ignored) { }
  }

  private boolean applyEvent(JsonNode raw, Map<String, Object> session) {
    if (raw == null || raw.isMissingNode() || raw.isNull()) return false;
    if (raw.isTextual()) {
      try { return applyEvent(mapper.readTree(raw.asText()), session); } catch (Exception ignored) { return false; }
    }
    if ("snapshot".equals(raw.path("type").asText())) {
      boolean changed = false;
      for (JsonNode item : raw.path("records")) changed |= applyEvent(item, session);
      return changed;
    }
    if ("session.event".equals(raw.path("method").asText())) return applyEvent(raw.path("params").path("event"), session);
    if ("event".equals(raw.path("type").asText())) return applyEvent(raw.path("event"), session);
    if (raw.path("value").isObject()) return applyEvent(raw.path("value"), session);
    JsonNode event = raw.path("event").isObject() ? raw.path("event") : raw;
    String type = event.path("type").asText("");
    if ("assistant/message".equals(type)) {
      String message = assistantText(event);
      if (message.isBlank()) return false;
      session.put("latestRole", "assistant");
      session.put("latestActor", "CodeWeave Agent");
      session.put("latestAction", "回复了");
      session.put("latestMessage", shorten(message));
      session.put("state", "运行中");
      session.put("detail", "Agent 正在回复");
      return true;
    }
    if ("turn/start".equals(type)) {
      session.put("state", "运行中");
      session.put("detail", "Agent 正在处理");
      return true;
    }
    if ("turn/end".equals(type)) {
      JsonNode reason = event.path("data").path("reason");
      String kind = reason.path("kind").asText("");
      String state = switch (kind) { case "error" -> "失败"; case "aborted", "interrupted", "blocked" -> "已中断"; default -> "已完成"; };
      session.put("state", state);
      session.put("detail", "Agent 已" + state);
      if ("error".equals(kind)) {
        String message = reason.path("message").asText(reason.path("error").asText("回复失败"));
        session.put("latestRole", "assistant"); session.put("latestActor", "CodeWeave Agent"); session.put("latestAction", "回复失败"); session.put("latestMessage", shorten(message));
      }
      return true;
    }
    return false;
  }

  private String assistantText(JsonNode event) {
    JsonNode content = event.path("data").path("message").path("content");
    if (content.isTextual()) return content.asText().trim();
    if (content.isArray()) {
      StringBuilder text = new StringBuilder();
      for (JsonNode block : content) if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) text.append(block.path("text").asText()).append('\n');
      return text.toString().trim();
    }
    return "";
  }

  private String shorten(String text) { return shorten(text, 120); }

  private String shorten(String text, int max) {
    String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    return value.length() > max ? value.substring(0, max - 1) + "…" : value;
  }
}
