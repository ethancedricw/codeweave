package com.codeweave.workspace;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.codeweave.config.SecretCryptoService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
  private static final String PROJECT_HASH = "cw:workspace:projects";
  private static final String SESSION_HASH = "cw:workspace:sessions";
  private static final String ACTIVITY_STREAM = "cw:workspace:activity";
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final SecretCryptoService crypto;
  private final WorkspaceProvisioningService provisioning;
  private final HttpClient http = HttpClient.newBuilder().build();
  public WorkspaceController(StringRedisTemplate redis, ObjectMapper mapper, SecretCryptoService crypto, WorkspaceProvisioningService provisioning) { this.redis = redis; this.mapper = mapper; this.crypto = crypto; this.provisioning = provisioning; }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    StpUtil.checkLogin();
    List<Map<String, Object>> sessions = sessions();
    List<Map<String, Object>> projects = projects();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("runningAgents", sessions.stream().filter(item -> "运行中".equals(item.get("state"))).count());
    metrics.put("pendingApprovals", 0);
    metrics.put("modelUsage", "0");
    metrics.put("membersOnline", memberCount());
    metrics.put("activeProjects", projects.stream().filter(item -> "ready".equals(item.get("status"))).count());
    List<Map<String, Object>> attention = sessions.stream().filter(item -> "失败".equals(item.get("state")) || "已中断".equals(item.get("state"))).map(item -> {
      Map<String, Object> notice = new LinkedHashMap<>();
      notice.put("id", item.get("id"));
      notice.put("title", item.getOrDefault("title", "Agent 会话"));
      notice.put("detail", item.getOrDefault("detail", "需要检查会话状态"));
      notice.put("tone", "danger");
      notice.put("route", "agent");
      return notice;
    }).limit(4).toList();
    return Map.of("metrics", metrics, "projects", projects, "sessions", latestSessions(sessions).stream().limit(6).toList(), "attention", attention, "activity", activities(), "serverTime", Instant.now().toString());
  }

  @GetMapping("/projects")
  public Map<String, Object> listProjects() {
    StpUtil.checkLogin();
    return Map.of("items", projects());
  }

  @PostMapping("/projects")
  public Map<String, Object> createProject(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String name = String.valueOf(request.getOrDefault("name", "")).trim();
    if (name.isBlank()) throw new IllegalArgumentException("项目名称不能为空");
    String id = "project-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Map<String, Object> project = projectFrom(id, request);
    project.putAll(provisioning.provision(id, String.valueOf(project.get("type")), String.valueOf(project.get("repoUrl")), String.valueOf(project.get("branch")), String.valueOf(project.getOrDefault("sourcePath", ""))));
    saveProject(project);
    recordActivity("project.created", "创建了项目", String.valueOf(project.get("name")), "mint");
    return publicProject(project);
  }

  @PutMapping("/projects/{id}")
  public Map<String, Object> updateProject(@PathVariable String id, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    if (redis.opsForHash().get(PROJECT_HASH, id) == null) throw new IllegalArgumentException("项目不存在");
    String name = String.valueOf(request.getOrDefault("name", "")).trim();
    if (name.isBlank()) throw new IllegalArgumentException("项目名称不能为空");
    Map<String, Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, id));
    Map<String, Object> project = projectFrom(id, request, existing);
    boolean keepSnapshot = "snapshot".equals(project.get("type")) && "ready".equals(existing.get("status")) && !String.valueOf(existing.getOrDefault("serverPath", "")).isBlank();
    if (keepSnapshot) {
      project.put("serverPath", existing.get("serverPath"));
      project.put("status", existing.get("status"));
      project.put("syncAt", existing.get("syncAt"));
      project.put("dshWorkspaceId", existing.getOrDefault("dshWorkspaceId", ""));
    } else {
      project.putAll(provisioning.provision(id, String.valueOf(project.get("type")), String.valueOf(project.get("repoUrl")), String.valueOf(project.get("branch")), String.valueOf(project.getOrDefault("sourcePath", ""))));
      project.put("dshWorkspaceId", "");
    }
    saveProject(project);
    recordActivity("project.updated", "更新了项目", String.valueOf(project.get("name")), "cyan");
    return publicProject(project);
  }

  @DeleteMapping("/projects/{id}")
  public Map<String, Object> deleteProject(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String, Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, id));
    redis.opsForHash().delete(PROJECT_HASH, id);
    if (existing != null) recordActivity("project.deleted", "删除了项目", String.valueOf(existing.getOrDefault("name", id)), "amber");
    return Map.of("deleted", true, "id", id);
  }

  @PostMapping("/projects/{id}/sync")
  public Map<String, Object> syncProject(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String, Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    existing.putAll(provisioning.provision(id, String.valueOf(existing.getOrDefault("type", "local")), String.valueOf(existing.getOrDefault("repoUrl", "")), String.valueOf(existing.getOrDefault("branch", "main")), String.valueOf(existing.getOrDefault("sourcePath", existing.getOrDefault("path", "")))));
    existing.put("updatedAt", Instant.now().toString());
    saveProject(existing);
    return publicProject(existing);
  }

  @PostMapping("/tasks")
  public Map<String, Object> createTask(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String id = "cw-" + String.format("%04d", new Random().nextInt(10000));
    Map<String, Object> event = new LinkedHashMap<>(); event.put("type", "task.created"); event.put("taskId", id); event.put("payload", request); event.put("createdAt", Instant.now().toString());
    redis.opsForStream().add("cw:session:events", event);
    recordActivity("task.created", "创建了协作任务", String.valueOf(request.getOrDefault("title", "未命名任务")), "cyan");
    return Map.of("id", id, "status", "queued", "title", request.getOrDefault("title", "未命名任务"));
  }

  @PostMapping("/shares")
  public Map<String, Object> createShare(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String code = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String key = "cw:share:" + code;
    redis.opsForValue().set(key, request.toString(), Duration.ofHours(24));
    return Map.of("code", code, "mode", request.getOrDefault("mode", "readonly"), "expiresInHours", 24, "path", "/share/" + code);
  }

  @PostMapping("/ragflow/datasets")
  public Map<String, Object> ragflowDatasets(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String url = String.valueOf(request.getOrDefault("url", "")).trim();
    String token = String.valueOf(request.getOrDefault("token", "")).trim();
    if (url.isBlank() || token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAGFlow URL 和 Token 不能为空");
    try {
      URI endpoint = URI.create(datasetEndpoint(url));
      if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())) || endpoint.getHost() == null) throw new IllegalArgumentException();
      HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
      HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "RAGFlow 返回了 " + response.statusCode());
      JsonNode root = mapper.readTree(response.body());
      JsonNode data = root.path("data");
      if (!data.isArray()) data = root.path("datasets");
      List<Map<String, Object>> items = new ArrayList<>();
      if (data.isArray()) for (JsonNode item : data) if (item.hasNonNull("id")) items.add(Map.of("id", item.path("id").asText(), "name", item.path("name").asText(item.path("id").asText())));
      return Map.of("items", items);
    } catch (ResponseStatusException ex) { throw ex; }
    catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 RAGFlow，请检查 URL、Token 和网络"); }
  }

  private List<Map<String, Object>> projects() {
    return redis.opsForHash().values(PROJECT_HASH).stream().map(this::parse).filter(Objects::nonNull).map(this::publicProject)
      .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updatedAt", "")), Comparator.reverseOrder())).toList();
  }

  private List<Map<String, Object>> sessions() {
    return redis.opsForHash().values(SESSION_HASH).stream().map(this::parse).filter(Objects::nonNull)
      .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updatedAt", "")), Comparator.reverseOrder())).toList();
  }

  private List<Map<String, Object>> latestSessions(List<Map<String, Object>> sessions) {
    Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
    for (Map<String, Object> item : sessions) {
      String key = String.valueOf(item.getOrDefault("projectId", ""));
      if (key.isBlank()) key = String.valueOf(item.getOrDefault("project", ""));
      if (key.isBlank()) key = String.valueOf(item.getOrDefault("id", UUID.randomUUID()));
      latest.putIfAbsent(key, item);
    }
    return new ArrayList<>(latest.values());
  }

  private List<Map<String, Object>> activities() {
    List<Map<String, Object>> items = new ArrayList<>();
    redis.opsForStream().range(ACTIVITY_STREAM, org.springframework.data.domain.Range.unbounded()).stream()
      .sorted(Comparator.comparing(record -> record.getId().getValue(), Comparator.reverseOrder())).limit(6)
      .forEach(record -> { Map<String, Object> item = new LinkedHashMap<>(); record.getValue().forEach((key, value) -> item.put(String.valueOf(key), value)); item.put("id", record.getId().getValue()); items.add(item); });
    return items;
  }

  private int memberCount() {
    Object raw = redis.opsForHash().size("cw:settings:users");
    long configured = raw instanceof Number number ? number.longValue() : 0;
    return (int) Math.max(1, configured);
  }

  private Map<String, Object> projectFrom(String id, Map<String, Object> request) { return projectFrom(id, request, null); }

  private Map<String, Object> projectFrom(String id, Map<String, Object> request, Map<String, Object> existing) {
    String type = String.valueOf(request.getOrDefault("type", "local"));
    String source = switch (type) { case "git" -> "Git 仓库"; case "snapshot" -> "快照项目"; default -> "本地目录"; };
    Map<String, Object> project = new LinkedHashMap<>();
    project.put("id", id);
    project.put("name", String.valueOf(request.getOrDefault("name", "未命名项目")).trim());
    project.put("type", type);
    project.put("source", source);
    project.put("repoUrl", request.getOrDefault("repoUrl", ""));
    String sourcePath = String.valueOf(request.getOrDefault("path", existing == null ? "" : existing.getOrDefault("sourcePath", existing.getOrDefault("path", "")))).trim();
    project.put("path", "");
    project.put("sourcePath", sourcePath);
    project.put("branch", request.getOrDefault("branch", "main"));
    project.put("description", request.getOrDefault("description", ""));
    project.put("ragflowEnabled", Boolean.TRUE.equals(request.get("ragflowEnabled")));
    project.put("ragflowUrl", request.getOrDefault("ragflowUrl", existing == null ? "" : existing.getOrDefault("ragflowUrl", "")));
    project.put("ragflowDatasetIds", request.getOrDefault("ragflowDatasetIds", existing == null ? List.of() : existing.getOrDefault("ragflowDatasetIds", List.of())));
    project.put("ragflowDatasetNames", request.getOrDefault("ragflowDatasetNames", existing == null ? List.of() : existing.getOrDefault("ragflowDatasetNames", List.of())));
    String token = String.valueOf(request.getOrDefault("ragflowToken", "")).trim();
    String previousCiphertext = existing == null ? "" : String.valueOf(existing.getOrDefault("ragflowTokenCiphertext", ""));
    project.put("ragflowTokenCiphertext", token.isBlank() ? previousCiphertext : crypto.encrypt(token));
    project.put("status", existing == null ? "preparing" : existing.getOrDefault("status", "preparing"));
    project.put("activeTasks", 0);
    project.put("updatedAt", Instant.now().toString());
    return project;
  }

  private Map<String, Object> publicProject(Map<String, Object> project) {
    Map<String, Object> result = new LinkedHashMap<>(project);
    result.remove("ragflowTokenCiphertext");
    result.remove("serverPath");
    result.remove("path");
    result.remove("sourcePath");
    result.put("ragflowTokenConfigured", !String.valueOf(project.getOrDefault("ragflowTokenCiphertext", "")).isBlank());
    boolean enabled = Boolean.TRUE.equals(project.get("ragflowEnabled"));
    Object ids = project.getOrDefault("ragflowDatasetIds", List.of());
    result.put("ragflowConfigured", enabled && ids instanceof Collection<?> collection && !collection.isEmpty());
    result.put("workspaceReady", "ready".equals(project.get("status")) && !String.valueOf(project.getOrDefault("serverPath", "")).isBlank());
    result.put("workspaceStatus", project.getOrDefault("status", "preparing"));
    result.put("workspaceError", project.getOrDefault("workspaceError", ""));
    return result;
  }

  private String datasetEndpoint(String url) {
    String base = url.trim().replaceAll("/+$", "");
    if (base.endsWith("/datasets")) return base;
    return base.endsWith("/api/v1") ? base + "/datasets" : base + "/api/v1/datasets";
  }

  private void saveProject(Map<String, Object> project) {
    try { redis.opsForHash().put(PROJECT_HASH, String.valueOf(project.get("id")), mapper.writeValueAsString(project)); }
    catch (Exception error) { throw new IllegalStateException("项目保存失败", error); }
  }

  private void recordActivity(String type, String action, String target, String tone) {
    redis.opsForStream().add(ACTIVITY_STREAM, Map.of("type", type, "action", action, "target", target, "tone", tone, "actor", "Lin", "createdAt", Instant.now().toString()));
  }

  private Map<String, Object> parse(Object value) {
    if (value == null) return null;
    try { return mapper.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {}); }
    catch (Exception ignored) { return null; }
  }
}
