package com.codeweave.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.codeweave.config.SecretCryptoService;
import com.codeweave.settings.ModelConfigRepository;
import com.codeweave.knowledge.RagflowRetrievalService;
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
  private final RagflowRetrievalService ragflow;
  /** Backward-compatible constructor for lightweight controller tests. */
  public AgentController(DeepSeekHarnessClient dsh, StringRedisTemplate redis, ObjectMapper mapper, ModelConfigRepository models, SecretCryptoService crypto) {
    this(dsh, redis, mapper, models, crypto, null);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public AgentController(DeepSeekHarnessClient dsh, StringRedisTemplate redis, ObjectMapper mapper, ModelConfigRepository models, SecretCryptoService crypto, RagflowRetrievalService ragflow) {
    this.dsh = dsh; this.redis = redis; this.mapper = mapper; this.models = models; this.crypto = crypto; this.ragflow = ragflow;
  }

  @ExceptionHandler(DeepSeekHarnessClient.DshException.class)
  public ResponseEntity<Map<String, String>> runtimeUnavailable(DeepSeekHarnessClient.DshException error) {
    return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
  }

  @PostMapping("/dsh-console")
  public ResponseEntity<Map<String, Object>> dshConsole(@RequestBody(required = false) Map<String, Object> request) {
    // The native console controls the whole runtime, so a project-scoped login cannot authorize it.
    StpUtil.checkLogin();
    var configured = models.findAll(StpUtil.getLoginIdAsString()).stream()
      .filter(model -> model.encryptedApiKey() != null && !model.encryptedApiKey().isBlank()).toList();
    if (configured.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "请先在设置中配置至少一个模型，DSH 不会使用其他账号的模型记录。");
    }
    dsh.checkConnection();
    // DSH persists its own provider settings. Clear this tenant first so an
    // account with no CodeWeave model configuration cannot reuse an old one.
    dsh.clearConsoleModels(StpUtil.getLoginIdAsString());
    String projectPath = request == null ? "" : String.valueOf(request.getOrDefault("serverPath", request.getOrDefault("projectPath", ""))).trim();
    String projectName = request == null ? "" : String.valueOf(request.getOrDefault("projectName", "")).trim();
    String projectId = request == null ? "" : String.valueOf(request.getOrDefault("projectId", "")).trim();
    if (!projectId.isBlank()) requireProjectMember(projectId);
    List<String> providers = configured.stream().map(model -> projectId.isBlank()
      ? dsh.syncConsoleModel(model, modelKey(model))
      : dsh.syncConsoleModel(model, modelKey(model), projectId)).toList();
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
      .body(Map.of("url", dsh.consoleUrl(workspaceId, providers, StpUtil.getLoginIdAsString(), projectId), "workspaceId", workspaceId));
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
    session.put("modelName", model.modelId());
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
    recordActivity("agent.started", "Agent 开始处理任务", String.valueOf(session.get("title")), "mint", String.valueOf(session.getOrDefault("projectId", "")));
    return Map.of("sessionId", sessionId, "runtime", "deepseek-harness", "session", session);
  }

  @PostMapping("/sessions/{sessionId}/messages")
  public Map<String, Object> prompt(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin(); String text = String.valueOf(request.getOrDefault("text", ""));
    if (text.isBlank() && !(request.get("receiptIds") instanceof List<?> values && !values.isEmpty())) throw new IllegalArgumentException("消息不能为空");
    Map<String, Object> session = loadOwnedSession(sessionId);
    requireProjectRole(session, false);
    var model = sessionModel(session);
    List<String> receiptIds = request.get("receiptIds") instanceof List<?> values
      ? values.stream().map(String::valueOf).toList() : List.of();
    List<Map<String, Object>> images = request.get("images") instanceof List<?> values
      ? values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList() : List.of();
    String projectId = String.valueOf(session.getOrDefault("projectId", "")).trim();
    String collaborationContext = projectContext(projectId);
    String knowledgeContext = ragflow == null ? "" : ragflow.retrieve(projectId, StpUtil.getLoginIdAsString(), text);
    StringBuilder prompt = new StringBuilder();
    if (!knowledgeContext.isBlank()) prompt.append(knowledgeContext);
    if (!collaborationContext.isBlank()) { if (prompt.length() > 0) prompt.append("\n\n"); prompt.append(collaborationContext); }
    if (prompt.length() > 0) prompt.append("\n\n用户最新指令：\n");
    prompt.append(text);
    String agentText = prompt.toString();
    dsh.prompt(sessionId, agentText, receiptIds, images, model, modelKey(model));
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
      recordActivity("agent.prompted", "提交了新的 Agent 指令", String.valueOf(session.getOrDefault("title", sessionId)), "cyan", String.valueOf(session.getOrDefault("projectId", "")));
    }
    return Map.of("accepted", true, "sessionId", sessionId);
  }

  @PutMapping("/sessions/{sessionId}/model")
  public Map<String, Object> selectModel(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    Map<String, Object> session = loadOwnedSession(sessionId);
    if (session.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    requireProjectRole(session, false);
    String modelId = String.valueOf(request.getOrDefault("modelId", "")).trim();
    String requestedName = String.valueOf(request.getOrDefault("modelName", "")).trim();
    var model = resolveModelSelection(modelId, requestedName);
    dsh.selectModelForSession(sessionId, model, modelKey(model));
    session.put("modelId", model.id()); session.put("modelName", model.modelId()); session.put("updatedAt", Instant.now().toString()); saveSession(session);
    return Map.of("modelId", model.id() + "::" + model.modelId(), "name", model.modelId());
  }

  @PostMapping("/sessions/{sessionId}/cancel")
  public Map<String, Object> cancel(@PathVariable String sessionId) {
    StpUtil.checkLogin();
    Map<String, Object> session = loadOwnedSession(sessionId);
    if (session.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    requireProjectRole(session, false);
    dsh.cancel(sessionId, StpUtil.getLoginIdAsString());
    session.put("state", "已中断"); session.put("detail", "Agent 已中断"); session.put("updatedAt", Instant.now().toString()); saveSession(session);
    return Map.of("accepted", true, "sessionId", sessionId);
  }

  @PostMapping(value = "/sessions/{sessionId}/attachments", consumes = "multipart/form-data")
  public Map<String, Object> uploadAttachment(@PathVariable String sessionId, @RequestPart("file") MultipartFile file) {
    StpUtil.checkLogin();
    Map<String, Object> session = loadOwnedSession(sessionId);
    if (session.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    }
    requireProjectRole(session, false);
    if (file.isEmpty() || file.getSize() > 50 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be between 1 byte and 50 MB.");
    try {
      Map<String, Object> uploaded = dsh.uploadFile(sessionId, file.getBytes(), file.getOriginalFilename(), StpUtil.getLoginIdAsString());
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
    loadOwnedSession(sessionId);
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

  /** Lightweight native-dsh summaries for the shared project collaboration rail. */
  @GetMapping("/project-sessions")
  public Map<String, Object> projectSessions(@RequestParam String projectId) {
    StpUtil.checkLogin();
    requireProjectMember(projectId);
    String projectPath = projectWorkspace(projectId).trim();
    String normalizedProject = projectPath.replace('\\', '/').replaceAll("/+$", "");
    String workspaceId = projectWorkspaceId(projectId);
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> raw : dsh.listSessions()) {
      if (Boolean.TRUE.equals(raw.get("blank"))) continue;
      String cwd = String.valueOf(raw.getOrDefault("cwd", "")).replace('\\', '/').replaceAll("/+$", "");
      if (!matchesProject(raw, cwd, normalizedProject, workspaceId)) continue;
      String id = String.valueOf(raw.getOrDefault("sessionId", raw.getOrDefault("id", "")));
      if (id.isBlank()) continue;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", id); item.put("dshSessionId", id); item.put("projectId", projectId);
      item.put("title", String.valueOf(raw.getOrDefault("title", "Agent 会话")));
      String ownerId = String.valueOf(raw.getOrDefault("ownerId", ""));
      if (ownerId.isBlank()) {
        var tenantSession = java.util.regex.Pattern.compile("^tenant-([0-9a-fA-F-]{36})-").matcher(id);
        if (tenantSession.find()) ownerId = tenantSession.group(1);
      }
      item.put("ownerId", ownerId);
      item.put("state", Boolean.TRUE.equals(raw.get("running")) ? "运行中" : "已完成");
      item.put("updatedAt", raw.getOrDefault("updatedAt", raw.getOrDefault("createdAt", Instant.now().toString())));
      item.put("cwd", cwd);
      item.put("origin", raw.getOrDefault("origin", ""));
      item.put("blank", Boolean.TRUE.equals(raw.get("blank")));
      flattenNativeSummary(item, raw);
      items.add(item);
    }
    items.sort(Comparator.comparingLong((Map<String, Object> item) -> timestamp(item.get("updatedAt"))).reversed());
    return Map.of("items", items.stream().limit(20).toList());
  }

  @SuppressWarnings("unchecked")
  private void flattenNativeSummary(Map<String, Object> item, Map<String, Object> raw) {
    Object projections = raw.get("projections");
    Map<String, Object> values = Map.of();
    if (projections instanceof Map<?, ?> projectionMap && projectionMap.get("values") instanceof Map<?, ?> valueMap) {
      values = mapper.convertValue(valueMap, new TypeReference<Map<String, Object>>() {});
    }
    String nativeTitle = String.valueOf(values.getOrDefault("title", "")).trim();
    if (!nativeTitle.isBlank() && !"null".equalsIgnoreCase(nativeTitle)) item.put("title", nativeTitle);
    Object outline = values.get("turnOutline");
    if (outline instanceof List<?> turns && !turns.isEmpty()) {
      Object last = turns.get(turns.size() - 1);
      if (last instanceof Map<?, ?> turn) {
        String prompt = String.valueOf(turn.get("prompt")).trim();
        String response = String.valueOf(turn.get("response")).trim();
        if (!prompt.isBlank() && !"null".equalsIgnoreCase(prompt)) item.put("latestPrompt", shorten(prompt, 240));
        if (!response.isBlank() && !"null".equalsIgnoreCase(response)) item.put("latestSummary", shorten(response, 240));
      }
    }
    Object stats = values.get("sessionStats");
    if (stats instanceof Map<?, ?> statsMap) item.put("turns", statsMap.get("turns") == null ? 0 : statsMap.get("turns"));
    Object modelSelection = values.get("modelSelection");
    if (modelSelection instanceof Map<?, ?> selection && selection.get("lastUsed") instanceof Map<?, ?> lastUsed) {
      Object model = lastUsed.get("model");
      if (model != null && !String.valueOf(model).isBlank()) item.put("model", model);
    }
  }

  private long timestamp(Object value) {
    if (value instanceof Number number) return number.longValue();
    try { return Instant.parse(String.valueOf(value)).toEpochMilli(); }
    catch (Exception ignored) { return 0; }
  }

  private boolean matchesProject(Map<String, Object> raw, String cwd, String projectPath, String workspaceId) {
    if (!workspaceId.isBlank()) {
      for (String key : List.of("workspaceId", "workspace", "workspaceIds", "workspaces")) {
        Object value = raw.get(key);
        if (containsValue(value, workspaceId)) return true;
      }
      Object projections = raw.get("projections");
      if (containsValue(projections, workspaceId)) return true;
    }
    if (projectPath.isBlank() || cwd.isBlank()) return false;
    return cwd.equals(projectPath) || cwd.startsWith(projectPath + "/");
  }

  private boolean containsValue(Object value, String expected) {
    if (value == null) return false;
    if (expected.equals(String.valueOf(value))) return true;
    if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(item -> containsValue(item, expected));
    if (value instanceof Iterable<?> values) {
      for (Object item : values) if (containsValue(item, expected)) return true;
    }
    return false;
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
    return new ModelConfigRepository.ModelConfig(base.id(), base.ownerId(), base.name(), selectedName, base.endpoint(), base.encryptedApiKey(), base.defaultModel(), base.updatedAt());
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

  private void requireProjectRole(Map<String,Object> session, boolean readOnly) {
    String projectId = String.valueOf(session.getOrDefault("projectId", "")).trim();
    if (projectId.isBlank()) return;
    Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (raw == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "项目不存在或无权访问");
    Map<String,Object> project = parse(raw);
    String userId = StpUtil.getLoginIdAsString();
    Object membersRaw = project.get("members");
    if (!(membersRaw instanceof java.util.Collection<?> members)) { if (userId.equals(String.valueOf(project.getOrDefault("ownerId", ""))) || "demo-user".equals(userId)) return; throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该项目成员"); }
    for (Object entry : members) if (entry instanceof Map<?,?> member && userId.equals(String.valueOf(member.get("userId")))) {
      Object roleRaw = member.containsKey("role") ? member.get("role") : "viewer"; String role = String.valueOf(roleRaw);
      if (readOnly || "admin".equals(role) || "developer".equals(role)) return;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前项目角色无权执行此操作");
  }

  private Map<String,Object> parse(Object value) { try { return mapper.readValue(String.valueOf(value), new TypeReference<Map<String,Object>>() {}); } catch (Exception ex) { return Map.of(); } }

  private void requireProjectMember(String projectId) {
    Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (raw == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "项目不存在或无权访问");
    Map<String,Object> project = parse(raw);
    String userId = StpUtil.getLoginIdAsString();
    if (userId.equals(String.valueOf(project.getOrDefault("ownerId", ""))) || "demo-user".equals(userId)) return;
    Object membersRaw = project.get("members");
    if (membersRaw instanceof java.util.Collection<?> members) {
      for (Object entry : members) if (entry instanceof Map<?,?> member && userId.equals(String.valueOf(member.get("userId")))) return;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该项目成员");
  }

  private Map<String, Object> loadOwnedSession(String id) {
    Map<String,Object> session = loadSession(id);
    if (session.isEmpty() || !StpUtil.getLoginIdAsString().equals(String.valueOf(session.getOrDefault("ownerId", "")))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session is unavailable.");
    }
    return session;
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

  private String projectWorkspaceId(String projectId) {
    if (projectId == null || projectId.isBlank()) return "";
    Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (raw == null) return "";
    try {
      Map<String, Object> project = mapper.readValue(String.valueOf(raw), new TypeReference<>() {});
      return String.valueOf(project.getOrDefault("dshWorkspaceId", "")).trim();
    } catch (Exception ignored) { return ""; }
  }

  private void recordActivity(String type, String action, String target, String tone) {
    recordActivity(type, action, target, tone, "");
  }

  private void recordActivity(String type, String action, String target, String tone, String projectId) {
    Map<String, String> event = new LinkedHashMap<>();
    event.put("type", type); event.put("action", action); event.put("target", target); event.put("tone", tone);
    event.put("actorId", StpUtil.getLoginIdAsString()); event.put("actor", StpUtil.getLoginIdAsString());
    event.put("projectId", projectId == null ? "" : projectId); event.put("createdAt", Instant.now().toString());
    redis.opsForStream().add(ACTIVITY_STREAM, event);
  }

  private String projectContext(String projectId) {
    if (projectId == null || projectId.isBlank()) return "";
    List<String> lines = new ArrayList<>();
    Map<Object,Object> presence = redis.opsForHash().entries("cw:workspace:presence:" + projectId);
    Instant threshold = Instant.now().minusSeconds(45);
    presence.forEach((user, seen) -> { try { Instant time = Instant.parse(String.valueOf(seen)); lines.add("- 成员「" + user + "」：" + (time.isAfter(threshold) ? "在线" : "离线") + "，最后活动 " + time); } catch (Exception ignored) {} });
    redis.opsForHash().values(SESSION_HASH).stream().map(value -> {
      try { return mapper.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {}); }
      catch (Exception ignored) { return Map.<String, Object>of(); }
    }).filter(item -> projectId.equals(String.valueOf(item.getOrDefault("projectId", ""))))
      .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updatedAt", "")), Comparator.reverseOrder()))
      .limit(5).forEach(item -> lines.add("- 会话「" + item.getOrDefault("title", "未命名") + "」：" + item.getOrDefault("state", "未知") + "，" + item.getOrDefault("detail", "无最新状态") + "（操作者：" + item.getOrDefault("latestActor", item.getOrDefault("ownerId", "未知")) + "）"));
    if (lines.isEmpty()) return "";
    return "当前 CodeWeave 项目协作上下文（仅使用已记录的信息）：\n" + String.join("\n", lines);
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
