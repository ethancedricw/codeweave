package com.codeweave.workspace;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.codeweave.config.SecretCryptoService;
import com.codeweave.agent.DeepSeekHarnessClient;
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
import java.util.stream.Stream;

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
  private final DeepSeekHarnessClient dsh;
  private final HttpClient http = HttpClient.newBuilder().build();
  public WorkspaceController(StringRedisTemplate redis, ObjectMapper mapper, SecretCryptoService crypto, WorkspaceProvisioningService provisioning, DeepSeekHarnessClient dsh) { this.redis = redis; this.mapper = mapper; this.crypto = crypto; this.provisioning = provisioning; this.dsh = dsh; }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    StpUtil.checkLogin();
    List<Map<String, Object>> sessions = sessions();
    List<Map<String, Object>> projects = projects();
    List<Map<String, Object>> collaboration = collaborationProjects();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("runningAgents", projects.stream().mapToLong(item -> ((Number) item.getOrDefault("runningAgents", 0L)).longValue()).sum());
    metrics.put("pendingApprovals", 0);
    metrics.put("unresolvedConflicts", null);
    metrics.put("membersOnline", collaboration.stream().flatMap(item -> ((List<Map<String,Object>>) item.getOrDefault("members", List.of())).stream()).filter(item -> Boolean.TRUE.equals(item.get("online"))).map(item -> String.valueOf(item.get("userId"))).distinct().count());
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
    return Map.of("metrics", metrics, "projects", projects, "collaboration", collaboration, "sessions", latestSessions(sessions).stream().limit(6).toList(), "attention", attention, "activity", activities(), "serverTime", Instant.now().toString());
  }

  @PostMapping("/presence")
  public Map<String, Object> heartbeat() {
    StpUtil.checkLogin();
    String seen = Instant.now().toString();
    redis.opsForValue().set("cw:online:" + StpUtil.getLoginIdAsString(), seen, Duration.ofMinutes(3));
    return Map.of("lastSeen", seen);
  }

  @GetMapping("/audit")
  public Map<String, Object> audit(@RequestParam(required = false) String query,
                                   @RequestParam(required = false) String actor,
                                   @RequestParam(required = false) String type) {
    StpUtil.checkLogin();
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    List<Map<String, Object>> items = activitiesAll().stream().filter(item -> {
      boolean matchesQuery = q.isBlank() || Stream.of("actor", "action", "target", "type").anyMatch(key -> String.valueOf(item.getOrDefault(key, "")).toLowerCase(Locale.ROOT).contains(q));
      boolean matchesActor = actor == null || actor.isBlank() || actor.equals(String.valueOf(item.getOrDefault("actor", "")));
      boolean matchesType = type == null || type.isBlank() || type.equals(String.valueOf(item.getOrDefault("type", "")));
      return matchesQuery && matchesActor && matchesType;
    }).limit(100).toList();
    return Map.of("items", items);
  }

  @GetMapping("/projects")
  public Map<String, Object> listProjects() {
    StpUtil.checkLogin();
    return Map.of("items", projects());
  }

  /** Returns the minimal directory used by project owners to select invitees. */
  @GetMapping("/users")
  public Map<String, Object> projectUsers() {
    StpUtil.checkLogin();
    List<Map<String, Object>> items = new ArrayList<>();
    redis.opsForHash().entries("cw:settings:users").forEach((id, value) -> {
      Map<String, Object> user = parse(value);
      if (user == null) return;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", String.valueOf(id));
      item.put("displayName", user.getOrDefault("displayName", user.getOrDefault("name", id)));
      item.put("email", user.getOrDefault("email", ""));
      item.put("status", user.getOrDefault("status", "active"));
      items.add(item);
    });
    items.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("displayName", ""))));
    return Map.of("items", items);
  }

  @GetMapping("/projects/{id}/members")
  public Map<String, Object> projectMembers(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (project == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    requireMember(project);
    List<Map<String,Object>> members = memberEntries(project);
    return Map.of("items", members);
  }

  @PostMapping("/projects/{id}/members/presence")
  public Map<String,Object> memberPresence(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (project == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    if (memberEntries(project).stream().noneMatch(member -> StpUtil.getLoginIdAsString().equals(String.valueOf(member.get("userId"))))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是此项目成员");
    String key = "cw:workspace:presence:" + id;
    redis.opsForHash().put(key, StpUtil.getLoginIdAsString(), Instant.now().toString());
    return Map.of("ok", true, "userId", StpUtil.getLoginIdAsString(), "lastSeen", Instant.now().toString());
  }

  @GetMapping("/projects/{id}/presence")
  public Map<String,Object> projectPresence(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (project == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    requireMember(project);
    List<Map<String,Object>> items = new ArrayList<>();
    for (Map<String,Object> member : memberEntries(project)) {
      String userId = String.valueOf(member.get("userId"));
      Map<String,Object> profile = parse(redis.opsForHash().get("cw:settings:users", userId));
      Map<String,Object> item = new LinkedHashMap<>();
      item.put("userId", userId);
      item.put("displayName", profile == null ? userId : profile.getOrDefault("displayName", userId));
      String seen = redis.opsForValue().get("cw:online:" + userId);
      item.put("online", seen != null);
      if (seen != null) item.put("lastSeen", seen);
      items.add(item);
    }
    return Map.of("items", items);
  }

  @PostMapping("/projects/{id}/members")
  public Map<String, Object> addProjectMember(@PathVariable String id, @RequestBody Map<String,Object> request) {
    StpUtil.checkLogin();
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (project == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    String userId = String.valueOf(request.getOrDefault("userId", "")).trim();
    if (userId.isBlank()) userId = String.valueOf(request.getOrDefault("email", "")).trim();
    if (userId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户不能为空");
    final String memberUserId = userId;
    String role = String.valueOf(request.getOrDefault("role", "developer"));
    if (!List.of("admin", "developer", "viewer").contains(role)) role = "developer";
    List<Map<String,Object>> members = memberEntries(project);
    members.removeIf(item -> memberUserId.equals(String.valueOf(item.get("userId"))));
    members.add(new LinkedHashMap<>(Map.of("userId", memberUserId, "role", role, "joinedAt", Instant.now().toString())));
    project.put("members", members); saveProject(project);
    recordActivity("project.member.added", "添加了项目成员", memberUserId, "cyan", id);
    return Map.of("member", members.stream().filter(item -> memberUserId.equals(String.valueOf(item.get("userId")))).findFirst().orElse(Map.of()));
  }

  @DeleteMapping("/projects/{id}/members/{userId}")
  public Map<String,Object> removeProjectMember(@PathVariable String id, @PathVariable String userId) {
    StpUtil.checkLogin();
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (project == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    List<Map<String,Object>> members = memberEntries(project); members.removeIf(item -> userId.equals(String.valueOf(item.get("userId"))));
    project.put("members", members); saveProject(project);
    recordActivity("project.member.removed", "移除了项目成员", userId, "amber", id);
    return Map.of("deleted", true);
  }

  @PutMapping("/projects/{id}/members/{userId}")
  public Map<String,Object> updateProjectMember(@PathVariable String id, @PathVariable String userId, @RequestBody Map<String,Object> request) {
    StpUtil.checkLogin();
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (project == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    String role = String.valueOf(request.getOrDefault("role", "developer"));
    List<Map<String,Object>> members = memberEntries(project);
    members.stream().filter(item -> userId.equals(String.valueOf(item.get("userId")))).findFirst().ifPresent(item -> item.put("role", role));
    project.put("members", members); saveProject(project); return Map.of("items", members);
  }

  @PostMapping("/projects")
  public Map<String, Object> createProject(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String name = String.valueOf(request.getOrDefault("name", "")).trim();
    if (name.isBlank()) throw new IllegalArgumentException("项目名称不能为空");
    String id = "project-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Map<String, Object> project = projectFrom(id, request);
    project.putAll(provisioning.provision(id, String.valueOf(project.get("type")), String.valueOf(project.get("repoUrl")), String.valueOf(project.get("branch")), String.valueOf(project.getOrDefault("sourcePath", "")), gitUsername(project), gitToken(project)));
    saveProject(project);
    recordActivity("project.created", "创建了项目", String.valueOf(project.get("name")), "mint", id);
    return publicProject(project);
  }

  @PutMapping("/projects/{id}")
  public Map<String, Object> updateProject(@PathVariable String id, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    Map<String,Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (existing == null) throw new IllegalArgumentException("项目不存在");
    requireMember(existing);
    String name = String.valueOf(request.getOrDefault("name", "")).trim();
    if (name.isBlank()) throw new IllegalArgumentException("项目名称不能为空");
    Map<String, Object> project = projectFrom(id, request, existing);
    if ("git".equals(project.get("type")) && !String.valueOf(project.getOrDefault("repoUrl", "")).equals(String.valueOf(existing.getOrDefault("repoUrl", ""))) && String.valueOf(request.getOrDefault("gitToken", "")).isBlank()) project.put("gitTokenCiphertext", "");
    boolean keepSnapshot = "snapshot".equals(project.get("type")) && "snapshot".equals(existing.get("type")) && "ready".equals(existing.get("status")) && !String.valueOf(existing.getOrDefault("serverPath", "")).isBlank();
    if (keepSnapshot) {
      project.put("serverPath", existing.get("serverPath"));
      project.put("status", existing.get("status"));
      project.put("syncAt", existing.get("syncAt"));
      project.put("dshWorkspaceId", existing.getOrDefault("dshWorkspaceId", ""));
    } else {
      project.putAll(provisioning.provision(id, String.valueOf(project.get("type")), String.valueOf(project.get("repoUrl")), String.valueOf(project.get("branch")), String.valueOf(project.getOrDefault("sourcePath", "")), gitUsername(project), gitToken(project)));
      project.put("dshWorkspaceId", "");
    }
    saveProject(project);
    recordActivity("project.updated", "更新了项目", String.valueOf(project.get("name")), "cyan", id);
    return publicProject(project);
  }

  @DeleteMapping("/projects/{id}")
  public Map<String, Object> deleteProject(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String, Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    requireOwner(existing);
    redis.opsForHash().delete(PROJECT_HASH, id);
    if (existing != null) recordActivity("project.deleted", "删除了项目", String.valueOf(existing.getOrDefault("name", id)), "amber", id);
    return Map.of("deleted", true, "id", id);
  }

  @PostMapping("/projects/{id}/sync")
  public Map<String, Object> syncProject(@PathVariable String id) {
    StpUtil.checkLogin();
    Map<String, Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, id));
    if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    requireMember(existing);
    existing.putAll(provisioning.provision(id, String.valueOf(existing.getOrDefault("type", "local")), String.valueOf(existing.getOrDefault("repoUrl", "")), String.valueOf(existing.getOrDefault("branch", "main")), String.valueOf(existing.getOrDefault("sourcePath", existing.getOrDefault("path", ""))), gitUsername(existing), gitToken(existing)));
    existing.put("updatedAt", Instant.now().toString());
    saveProject(existing);
    return publicProject(existing);
  }

  @PostMapping("/projects/git/test")
  public Map<String, Object> testGit(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String repoUrl = String.valueOf(request.getOrDefault("repoUrl", "")).trim();
    String branch = String.valueOf(request.getOrDefault("branch", "main")).trim();
    String username = String.valueOf(request.getOrDefault("gitUsername", "")).trim();
    String token = String.valueOf(request.getOrDefault("gitToken", "")).trim();
    String projectId = String.valueOf(request.getOrDefault("projectId", "")).trim();
    if (!projectId.isBlank()) {
      Map<String, Object> existing = parse(redis.opsForHash().get(PROJECT_HASH, projectId));
      if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
      requireMember(existing);
      if (username.isBlank()) username = gitUsername(existing);
      if (token.isBlank()) {
        if (!repoUrl.equals(String.valueOf(existing.getOrDefault("repoUrl", "")).trim())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更换仓库地址时请重新填写访问令牌");
        token = gitToken(existing);
      }
    }
    try {
      provisioning.testGit(repoUrl, branch, username, token);
      return Map.of("status", "ready", "message", "Git 仓库连接成功，可以拉取");
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
    } catch (IllegalStateException error) {
      String message = error.getMessage();
      if (message == null || message.isBlank()) message = "Git 仓库连接失败，请检查地址、分支和访问凭据";
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Git 连接失败：" + message);
    }
  }

  @PostMapping("/tasks")
  public Map<String, Object> createTask(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String id = "cw-" + String.format("%04d", new Random().nextInt(10000));
    Map<String, Object> event = new LinkedHashMap<>(); event.put("type", "task.created"); event.put("taskId", id); event.put("payload", request); event.put("createdAt", Instant.now().toString());
    redis.opsForStream().add("cw:session:events", event);
    recordActivity("task.created", "创建了协作任务", String.valueOf(request.getOrDefault("title", "未命名任务")), "cyan", String.valueOf(request.getOrDefault("projectId", "")));
    return Map.of("id", id, "status", "queued", "title", request.getOrDefault("title", "未命名任务"));
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
    String userId = StpUtil.getLoginIdAsString();
    List<Map<String, Object>> sessions = redis.opsForHash().values(SESSION_HASH).stream().map(this::parse).filter(Objects::nonNull).toList();
    List<Map<String, Object>> nativeSessions;
    try { nativeSessions = dsh.listSessions(); }
    catch (RuntimeException ex) { nativeSessions = List.of(); }
    final List<Map<String, Object>> nativeItems = nativeSessions;
    return redis.opsForHash().values(PROJECT_HASH).stream().map(this::parse).filter(Objects::nonNull)
      .filter(project -> memberEntries(project).stream().anyMatch(member -> userId.equals(String.valueOf(member.get("userId")))))
      .map(project -> {
        Map<String, Object> result = publicProject(project);
        result.put("activeTasks", activeTaskCount(project, sessions, nativeItems));
        List<Map<String, Object>> runningSessions = sessions.stream().map(session -> {
          Map<String, Object> copy = new HashMap<>(session);
          if (!"运行中".equals(copy.get("state"))) copy.put("state", "已完成");
          return copy;
        }).toList();
        result.put("runningAgents", activeTaskCount(project, runningSessions, nativeItems));
        return result;
      })
      .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updatedAt", "")), Comparator.reverseOrder())).toList();
  }

  static long activeTaskCount(Map<String, Object> project, List<Map<String, Object>> sessions, List<Map<String, Object>> nativeSessions) {
    Map<String, Boolean> active = new HashMap<>();
    String projectId = String.valueOf(project.get("id"));
    for (Map<String, Object> session : sessions) {
      if (!projectId.equals(String.valueOf(session.get("projectId")))) continue;
      String id = String.valueOf(session.getOrDefault("dshSessionId", session.getOrDefault("id", "")));
      if (!id.isBlank()) active.put(id, Set.of("运行中", "等待审批", "待审批").contains(String.valueOf(session.get("state"))));
    }
    String path = String.valueOf(project.getOrDefault("serverPath", "")).replace('\\', '/').replaceAll("/+$", "");
    String workspaceId = String.valueOf(project.getOrDefault("dshWorkspaceId", "")).trim();
    for (Map<String, Object> session : nativeSessions) {
      if (Boolean.TRUE.equals(session.get("blank"))) continue;
      String cwd = String.valueOf(session.getOrDefault("cwd", "")).replace('\\', '/').replaceAll("/+$", "");
      boolean matches = !path.isBlank() && (cwd.equals(path) || cwd.startsWith(path + "/"));
      if (!workspaceId.isBlank()) for (String key : List.of("workspaceId", "workspace", "workspaceIds", "workspaces", "projections")) matches |= containsWorkspace(session.get(key), workspaceId);
      if (!matches) continue;
      String id = String.valueOf(session.getOrDefault("sessionId", session.getOrDefault("id", "")));
      if (!id.isBlank()) active.put(id, Boolean.TRUE.equals(session.get("running")));
    }
    return active.values().stream().filter(Boolean.TRUE::equals).count();
  }

  private static boolean containsWorkspace(Object value, String id) {
    if (value == null) return false;
    if (id.equals(String.valueOf(value))) return true;
    if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(item -> containsWorkspace(item, id));
    if (value instanceof Collection<?> items) return items.stream().anyMatch(item -> containsWorkspace(item, id));
    return false;
  }

  private List<Map<String, Object>> sessions() {
    return redis.opsForHash().values(SESSION_HASH).stream().map(this::parse).filter(Objects::nonNull)
      .filter(session -> sessionVisibleToCurrentUser(session))
      .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updatedAt", "")), Comparator.reverseOrder())).toList();
  }

  private boolean sessionVisibleToCurrentUser(Map<String,Object> session) {
    String userId = StpUtil.getLoginIdAsString();
    if (userId.equals(String.valueOf(session.getOrDefault("ownerId", "")))) return true;
    String projectId = String.valueOf(session.getOrDefault("projectId", "")).trim();
    if (projectId.isBlank()) return false;
    Map<String,Object> project = parse(redis.opsForHash().get(PROJECT_HASH, projectId));
    return project != null && memberEntries(project).stream().anyMatch(member -> userId.equals(String.valueOf(member.get("userId"))));
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
    return activitiesAll().stream().limit(6).toList();
  }

  private List<Map<String, Object>> activitiesAll() {
    List<Map<String, Object>> items = new ArrayList<>();
    redis.opsForStream().range(ACTIVITY_STREAM, org.springframework.data.domain.Range.unbounded()).stream()
      .sorted(Comparator.comparing(record -> record.getId().getValue(), Comparator.reverseOrder()))
      .forEach(record -> { Map<String, Object> item = new LinkedHashMap<>(); record.getValue().forEach((key, value) -> item.put(String.valueOf(key), value)); item.put("id", record.getId().getValue()); items.add(activityDisplayNames(item)); });
    return items;
  }

  Map<String, Object> activityDisplayNames(Map<String, Object> item) {
    String actorId = String.valueOf(item.getOrDefault("actorId", item.getOrDefault("actor", "")));
    item.put("actor", activityUserName(actorId, "团队成员"));
    String type = String.valueOf(item.getOrDefault("type", ""));
    if ("project.member.added".equals(type) || "project.member.removed".equals(type)) {
      String targetId = String.valueOf(item.getOrDefault("targetId", item.getOrDefault("target", "")));
      item.put("targetId", targetId);
      item.put("target", activityUserName(targetId, "已删除成员"));
    }
    return item;
  }

  private String activityUserName(String userId, String fallback) {
    Map<String, Object> user = parse(redis.opsForHash().get("cw:settings:users", userId));
    if (user != null) for (String field : List.of("displayName", "name", "email")) {
      Object value = user.get(field);
      if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
    }
    // Legacy records may already contain a readable name instead of a user ID.
    if (!userId.isBlank() && !userId.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") && !"demo-user".equals(userId)) return userId;
    return fallback;
  }

  private List<Map<String, Object>> activitiesForProject(String projectId) {
    if (projectId == null || projectId.isBlank()) return List.of();
    return activities().stream().filter(item -> projectId.equals(String.valueOf(item.getOrDefault("projectId", "")))).toList();
  }

  private List<Map<String, Object>> collaborationProjects() {
    String currentUser = StpUtil.getLoginIdAsString();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> project : projects()) {
      List<Map<String, Object>> members = memberEntries(project);
      boolean belongs = members.stream().anyMatch(member -> currentUser.equals(String.valueOf(member.get("userId"))));
      if (!belongs) continue;

      List<Map<String,Object>> visible = new ArrayList<>();
      for (Map<String,Object> member : members) {
        String userId = String.valueOf(member.getOrDefault("userId", ""));
        Instant seen = null;
        String heartbeat = redis.opsForValue().get("cw:online:" + userId);
        if (heartbeat != null) seen = Instant.parse(heartbeat);
        Map<String,Object> profile = parse(redis.opsForHash().get("cw:settings:users", userId));
        String displayName = userId;
        if (profile != null) for (String field : List.of("displayName", "name", "email")) {
          Object value = profile.get(field); if (value != null && !String.valueOf(value).isBlank()) { displayName = String.valueOf(value); break; }
        }
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("userId", userId); item.put("displayName", displayName); item.put("role", member.getOrDefault("role", "developer"));
        item.put("online", seen != null); if (seen != null) item.put("lastSeen", seen.toString());
        visible.add(item);
      }
      Map<String,Object> group = new LinkedHashMap<>(); group.put("projectId", project.get("id")); group.put("projectName", project.get("name"));
      group.put("onlineCount", visible.stream().filter(item -> Boolean.TRUE.equals(item.get("online"))).count()); group.put("members", visible); result.add(group);
    }
    return result;
  }

  private Map<String, Object> projectFrom(String id, Map<String, Object> request) { return projectFrom(id, request, null); }

  private Map<String, Object> projectFrom(String id, Map<String, Object> request, Map<String, Object> existing) {
    String type = String.valueOf(request.getOrDefault("type", "local"));
    String source = switch (type) { case "git" -> "Git 仓库"; case "snapshot" -> "快照项目"; default -> "本地目录"; };
    Map<String, Object> project = new LinkedHashMap<>();
    project.put("id", id);
    project.put("ownerId", existing == null ? StpUtil.getLoginIdAsString() : existing.getOrDefault("ownerId", StpUtil.getLoginIdAsString()));
    project.put("name", String.valueOf(request.getOrDefault("name", "未命名项目")).trim());
    project.put("type", type);
    project.put("source", source);
    project.put("repoUrl", request.getOrDefault("repoUrl", ""));
    String sourcePath = String.valueOf(request.getOrDefault("path", existing == null ? "" : existing.getOrDefault("sourcePath", existing.getOrDefault("path", "")))).trim();
    project.put("path", "");
    project.put("sourcePath", sourcePath);
    project.put("branch", request.getOrDefault("branch", "main"));
    project.put("gitUsername", "git".equals(type) ? request.getOrDefault("gitUsername", existing == null ? "" : existing.getOrDefault("gitUsername", "")) : "");
    String gitToken = String.valueOf(request.getOrDefault("gitToken", "")).trim();
    String previousGitToken = existing == null ? "" : String.valueOf(existing.getOrDefault("gitTokenCiphertext", ""));
    project.put("gitTokenCiphertext", "git".equals(type) ? (gitToken.isBlank() ? previousGitToken : crypto.encrypt(gitToken)) : "");
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
    if (existing != null && existing.get("members") != null) project.put("members", existing.get("members"));
    else project.put("members", List.of(Map.of("userId", StpUtil.getLoginIdAsString(), "role", "admin", "joinedAt", Instant.now().toString())));
    project.put("updatedAt", Instant.now().toString());
    return project;
  }

  private List<Map<String,Object>> memberEntries(Map<String,Object> project) {
    Object raw = project.get("members"); List<Map<String,Object>> result = new ArrayList<>();
    if (raw instanceof Collection<?> collection) for (Object item : collection) if (item instanceof Map<?,?> map) { Map<String,Object> copy = new LinkedHashMap<>(); map.forEach((k,v)->copy.put(String.valueOf(k),v)); result.add(copy); }
    if (result.isEmpty()) result.add(new LinkedHashMap<>(Map.of("userId", String.valueOf(project.getOrDefault("ownerId", "demo-user")), "role", "admin")));
    return result;
  }

  private void requireMember(Map<String,Object> project) {
    String userId = StpUtil.getLoginIdAsString();
    if (memberEntries(project).stream().noneMatch(member -> userId.equals(String.valueOf(member.get("userId"))))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该项目成员");
  }

  private void requireOwner(Map<String,Object> project) {
    String userId = StpUtil.getLoginIdAsString();
    // 1. 允许项目创建者删除
    if (userId.equals(String.valueOf(project.getOrDefault("ownerId", "")))) return;
    // 2. 允许管理员删除所有项目 - 从 Redis 读取用户角色
    Object userRaw = redis.opsForHash().get("cw:settings:users", userId);
    if (userRaw != null) {
      try {
        Map<String, Object> user = mapper.readValue(String.valueOf(userRaw), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        if ("admin".equals(String.valueOf(user.getOrDefault("role", "")))) return;
      } catch (Exception ignored) { }
    }
    // demo-user 默认是管理员
    if ("demo-user".equals(userId)) return;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有项目创建者或管理员可以删除项目");
  }

  private Map<String, Object> publicProject(Map<String, Object> project) {
    Map<String, Object> result = new LinkedHashMap<>(project);
    result.remove("ragflowTokenCiphertext");
    result.remove("gitTokenCiphertext");
    result.remove("serverPath");
    result.remove("path");
    result.remove("sourcePath");
    result.put("ragflowTokenConfigured", !String.valueOf(project.getOrDefault("ragflowTokenCiphertext", "")).isBlank());
    result.put("gitTokenConfigured", !String.valueOf(project.getOrDefault("gitTokenCiphertext", "")).isBlank());
    boolean enabled = Boolean.TRUE.equals(project.get("ragflowEnabled"));
    Object ids = project.getOrDefault("ragflowDatasetIds", List.of());
    result.put("ragflowConfigured", enabled && ids instanceof Collection<?> collection && !collection.isEmpty());
    result.put("workspaceReady", "ready".equals(project.get("status")) && !String.valueOf(project.getOrDefault("serverPath", "")).isBlank());
    result.put("workspaceStatus", project.getOrDefault("status", "preparing"));
    result.put("workspaceError", project.getOrDefault("workspaceError", ""));
    return result;
  }

  private String gitUsername(Map<String, Object> project) {
    return String.valueOf(project.getOrDefault("gitUsername", "")).trim();
  }

  private String gitToken(Map<String, Object> project) {
    String ciphertext = String.valueOf(project.getOrDefault("gitTokenCiphertext", ""));
    if (ciphertext.isBlank()) return "";
    try { return crypto.decrypt(ciphertext); } catch (Exception ignored) { return ""; }
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
    recordActivity(type, action, target, tone, "");
  }

  private void recordActivity(String type, String action, String target, String tone, String projectId) {
    redis.opsForStream().add(ACTIVITY_STREAM, Map.of("type", type, "action", action, "target", target, "tone", tone, "actorId", StpUtil.getLoginIdAsString(), "actor", StpUtil.getLoginIdAsString(), "projectId", projectId == null ? "" : projectId, "createdAt", Instant.now().toString()));
  }

  private Map<String, Object> parse(Object value) {
    if (value == null) return null;
    try { return mapper.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {}); }
    catch (Exception ignored) { return null; }
  }
}
