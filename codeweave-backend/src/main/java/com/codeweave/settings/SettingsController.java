package com.codeweave.settings;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeweave.config.SecretCryptoService;
import com.codeweave.auth.AccessControlService;
import com.codeweave.storage.MinioStorageService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/** Settings APIs used by the workspace settings drawer. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
  private static final String USER_HASH = "cw:settings:users";
  private static final String ROLE_HASH = "cw:settings:roles";
  private static final String DEPARTMENT_HASH = "cw:settings:departments";
  private static final String RAGFLOW_HASH_PREFIX = "cw:settings:ragflow:";
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final ModelConfigRepository modelRepository;
  private final SecretCryptoService crypto;
  private final AccessControlService access;
  private final PasswordEncoder passwordEncoder;
  private final JdbcTemplate db;
  private final MinioStorageService storage;
  private final HttpClient http = HttpClient.newBuilder().build();
  private final ModelCatalogService catalog;

  public SettingsController(StringRedisTemplate redis, ObjectMapper mapper, ModelConfigRepository modelRepository, SecretCryptoService crypto, AccessControlService access, PasswordEncoder passwordEncoder, JdbcTemplate db, MinioStorageService storage, ModelCatalogService catalog) {
    this.redis = redis;
    this.mapper = mapper;
    this.modelRepository = modelRepository;
    this.crypto = crypto;
    this.access = access;
    this.passwordEncoder = passwordEncoder;
    this.db = db;
    this.storage = storage;
    this.catalog = catalog;
  }

  @GetMapping("/models")
  public Map<String, Object> models() {
    StpUtil.checkLogin();
    String owner = currentUser();
    List<Map<String, Object>> items = modelRepository.findAll(owner).stream().map(this::publicModel).toList();
    return Map.of("items", items);
  }

  @GetMapping("/models/catalog")
  public Map<String, Object> modelCatalog() {
    StpUtil.checkLogin();
    List<Map<String, Object>> items = new ArrayList<>();
    for (ModelConfigRepository.ModelConfig config : modelRepository.findAll(currentUser())) {
      if (config.encryptedApiKey() == null || config.encryptedApiKey().isBlank()) continue;
      List<String> discovered = catalog.modelIds(config);
      Set<String> ids = new LinkedHashSet<>();
      if (!config.name().isBlank()) ids.add(config.name());
      ids.addAll(discovered);
      for (String id : ids) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", config.id() + "::" + id);
        item.put("configId", config.id());
        item.put("modelId", id);
        item.put("name", id);
        item.put("providerName", config.name());
        item.put("defaultModel", config.defaultModel() && id.equals(config.name()));
        item.put("discovered", discovered.contains(id));
        items.add(item);
      }
    }
    return Map.of("items", items);
  }

  @GetMapping("/ragflow")
  public Map<String, Object> ragflow() {
    StpUtil.checkLogin();
    Map<String, Object> config = ragflowConfig(currentUser());
    return Map.of("url", config.getOrDefault("url", ""), "tokenConfigured", !String.valueOf(config.getOrDefault("tokenCiphertext", "")).isBlank());
  }

  @PutMapping("/ragflow")
  public Map<String, Object> saveRagflow(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String url = String.valueOf(request.getOrDefault("url", "")).trim();
    if (url.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAGFlow URL 不能为空");
    try {
      URI parsed = URI.create(url);
      if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost() == null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAGFlow URL 格式无效"); }
    String owner = currentUser(); Map<String, Object> old = ragflowConfig(owner); Map<String, Object> next = new LinkedHashMap<>();
    next.put("url", url);
    String token = String.valueOf(request.getOrDefault("token", "")).trim();
    next.put("tokenCiphertext", token.isBlank() ? old.getOrDefault("tokenCiphertext", "") : crypto.encrypt(token));
    redis.opsForValue().set(RAGFLOW_HASH_PREFIX + owner, write(next));
    return Map.of("url", url, "tokenConfigured", !String.valueOf(next.get("tokenCiphertext")).isBlank());
  }

  @GetMapping("/ragflow/datasets")
  public Map<String, Object> ragflowDatasets() {
    StpUtil.checkLogin();
    Map<String, Object> config = ragflowConfig(currentUser());
    String url = String.valueOf(config.getOrDefault("url", "")); String ciphertext = String.valueOf(config.getOrDefault("tokenCiphertext", ""));
    if (url.isBlank() || ciphertext.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先在设置中保存 RAGFlow URL 和 Token");
    try {
      URI endpoint = URI.create(datasetEndpoint(url));
      HttpRequest request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + crypto.decrypt(ciphertext)).header("Accept", "application/json").GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "RAGFlow 返回了 " + response.statusCode());
      JsonNode root = mapper.readTree(response.body()); JsonNode data = root.path("data"); if (!data.isArray()) data = root.path("datasets");
      List<Map<String, Object>> items = new ArrayList<>();
      if (data.isArray()) for (JsonNode item : data) if (item.hasNonNull("id")) items.add(Map.of("id", item.path("id").asText(), "name", item.path("name").asText(item.path("id").asText())));
      return Map.of("items", items);
    } catch (ResponseStatusException ex) { throw ex; }
    catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 RAGFlow，请检查服务地址和 Token"); }
  }

  @PostMapping("/ragflow/test")
  public Map<String, Object> testRagflow(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String url = String.valueOf(request.getOrDefault("url", "")).trim();
    String token = String.valueOf(request.getOrDefault("token", "")).trim();
    if (token.isBlank()) {
      Map<String, Object> saved = ragflowConfig(currentUser());
      token = saved.isEmpty() ? "" : crypto.decrypt(String.valueOf(saved.getOrDefault("tokenCiphertext", "")));
    }
    if (url.isBlank() || token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 RAGFlow URL 和 Token");
    try {
      URI endpoint = URI.create(datasetEndpoint(url));
      if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())) || endpoint.getHost() == null) throw new IllegalArgumentException();
      HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
      HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "RAGFlow 返回了 " + response.statusCode());
      JsonNode root = mapper.readTree(response.body()); JsonNode data = root.path("data"); if (!data.isArray()) data = root.path("datasets");
      return Map.of("status", "ready", "message", "连接成功，可访问 " + (data.isArray() ? data.size() : 0) + " 个知识库");
    } catch (ResponseStatusException ex) { throw ex; }
    catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 RAGFlow，请检查服务地址和 Token"); }
  }

  @PostMapping("/models")
  public Map<String, Object> createModel(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    requireText(request, "name");
    requireText(request, "endpoint");
    requireText(request, "apiKey");
    String owner = currentUser(); String id = UUID.randomUUID().toString();
    boolean defaultModel = Boolean.TRUE.equals(request.get("defaultModel"));
    if (defaultModel) modelRepository.clearDefaults(owner);
    ModelConfigRepository.ModelConfig item = new ModelConfigRepository.ModelConfig(id, owner, String.valueOf(request.get("name")), String.valueOf(request.get("endpoint")), encryptKey(request.get("apiKey")), defaultModel, Instant.now());
    modelRepository.save(item);
    return publicModel(item);
  }

  @PutMapping("/models/{id}")
  public Map<String, Object> updateModel(@PathVariable String id, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String owner = currentUser();
    ModelConfigRepository.ModelConfig old = modelRepository.find(owner, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
    String apiKey = request.get("apiKey") != null && !String.valueOf(request.get("apiKey")).isBlank() ? encryptKey(request.get("apiKey")) : Objects.requireNonNullElse(old.encryptedApiKey(), "");
    boolean defaultModel = request.containsKey("defaultModel") ? Boolean.TRUE.equals(request.get("defaultModel")) : old.defaultModel();
    if (defaultModel) modelRepository.clearDefaults(owner);
    ModelConfigRepository.ModelConfig item = new ModelConfigRepository.ModelConfig(id, owner, String.valueOf(request.getOrDefault("name", old.name())), String.valueOf(request.getOrDefault("endpoint", old.endpoint())), apiKey, defaultModel, Instant.now());
    modelRepository.save(item);
    return publicModel(item);
  }

  @DeleteMapping("/models/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteModel(@PathVariable String id) {
    StpUtil.checkLogin();
    String owner = currentUser();
    ModelConfigRepository.ModelConfig deleted = modelRepository.find(owner, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
    modelRepository.delete(owner, id);
    if (deleted.defaultModel()) {
      modelRepository.findAll(owner).stream().findFirst().ifPresent(next -> modelRepository.save(new ModelConfigRepository.ModelConfig(next.id(), next.ownerId(), next.name(), next.endpoint(), next.encryptedApiKey(), true, Instant.now())));
    }
  }

  @PostMapping("/models/{id}/test")
  public Map<String, Object> testModel(@PathVariable String id) {
    StpUtil.checkLogin();
    ModelConfigRepository.ModelConfig item = modelRepository.find(currentUser(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
    return probeModel(item.name(), item.endpoint(), item.encryptedApiKey().isBlank() ? "" : crypto.decrypt(item.encryptedApiKey()));
  }

  @PostMapping("/models/test-connection")
  public Map<String, Object> testModelConnection(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String endpoint = Objects.toString(request.get("endpoint"), "").trim();
    String apiKey = Objects.toString(request.get("apiKey"), "").trim();
    if (apiKey.isBlank() && request.get("id") != null) {
      var saved = modelRepository.find(currentUser(), String.valueOf(request.get("id")))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model configuration not found."));
      if (!saved.encryptedApiKey().isBlank()) apiKey = crypto.decrypt(saved.encryptedApiKey());
    }
    return probeModel(Objects.toString(request.get("name"), "").trim(), endpoint, apiKey);
  }

  @PostMapping("/models/discover")
  public Map<String, Object> discoverModels(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String endpoint = Objects.toString(request.get("endpoint"), "").trim();
    String apiKey = Objects.toString(request.get("apiKey"), "").trim();
    if (apiKey.isBlank() && request.get("id") != null) {
      var saved = modelRepository.find(currentUser(), String.valueOf(request.get("id")))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model configuration not found."));
      if (!saved.encryptedApiKey().isBlank()) apiKey = crypto.decrypt(saved.encryptedApiKey());
    }
    if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先填写 API Key");
    try {
      URI base = URI.create(endpoint);
      if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme())) || base.getHost() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口地址格式无效");
      }
      HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$", "") + "/models"))
        .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + apiKey)
        .header("Accept", "application/json").GET().build();
      HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型服务返回 HTTP " + response.statusCode());
      JsonNode root = mapper.readTree(response.body());
      JsonNode data = root.path("data");
      if (!data.isArray()) data = root.path("models");
      if (!data.isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "接口没有返回兼容的模型列表");
      List<Map<String, String>> items = new ArrayList<>();
      for (JsonNode item : data) {
        String id = item.isTextual() ? item.asText().trim() : item.path("id").asText("").trim();
        if (!id.isBlank()) items.add(Map.of("id", id, "name", item.path("name").asText(id)));
      }
      return Map.of("items", items);
    } catch (ResponseStatusException ex) { throw ex; }
    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "读取模型列表被中断"); }
    catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取模型列表，请检查接口地址和 API Key"); }
  }

  private Map<String, Object> probeModel(String name, String endpoint, String apiKey) {
    if (apiKey.isBlank()) return Map.of("status", "missing_api_key", "message", "Enter an API key before testing the connection.");
    if (name.isBlank()) return Map.of("status", "invalid_model", "message", "Enter the provider's model ID.");
    try {
      URI uri = URI.create(endpoint);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
        return Map.of("status", "invalid_endpoint", "message", "Enter a valid HTTP or HTTPS API base URL.");
      }
      var probe = HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$", "") + "/chat/completions"))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
          "model", name, "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")), "max_tokens", 8, "stream", false
        )))).build();
      var response = http.send(probe, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        String providerMessage = "";
        try { providerMessage = mapper.readTree(response.body()).path("error").path("message").asText("").trim(); } catch (Exception ignored) { }
        if (providerMessage.length() > 300) providerMessage = providerMessage.substring(0, 300);
        String detail = providerMessage.isBlank() ? "Check the API key, model ID and API base URL." : providerMessage;
        return Map.of("status", "error", "message", "Model connection failed (HTTP " + response.statusCode() + "). " + detail);
      }
      if (!mapper.readTree(response.body()).path("choices").isArray()) {
        return Map.of("status", "error", "message", "The endpoint did not return an OpenAI-compatible chat response.");
      }
      return Map.of("status", "ready", "message", "Model connection successful.");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return Map.of("status", "error", "message", "Model connection test was interrupted.");
    } catch (Exception error) {
      return Map.of("status", "error", "message", "Cannot connect to the model. Check the API base URL and network.");
    }
  }

  @GetMapping("/roles")
  public Map<String, Object> roles() {
    access.require("org:manage");
    seedRolesIfNeeded();
    List<Map<String, Object>> items = new ArrayList<>();
    redis.opsForHash().entries(ROLE_HASH).forEach((id, value) -> { Map<String, Object> item = read((String) value); item.put("id", id.toString()); items.add(item); });
    items.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("name", ""))));
    return Map.of("items", items);
  }

  @PostMapping("/roles")
  public Map<String, Object> createRole(@RequestBody Map<String, Object> request) {
    access.require("role:manage"); requireText(request, "name");
    seedRolesIfNeeded();
    String id = UUID.randomUUID().toString();
    Map<String, Object> item = roleFrom(request, false);
    redis.opsForHash().put(ROLE_HASH, id, write(item)); item.put("id", id); return item;
  }

  @PutMapping("/roles/{id}")
  public Map<String, Object> updateRole(@PathVariable String id, @RequestBody Map<String, Object> request) {
    access.require("role:manage"); seedRolesIfNeeded();
    Object raw = redis.opsForHash().get(ROLE_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
    Map<String, Object> old = read(raw.toString());
    if (Boolean.TRUE.equals(old.get("builtIn"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "系统角色不可编辑");
    requireText(request, "name"); Map<String, Object> item = roleFrom(request, false);
    redis.opsForHash().put(ROLE_HASH, id, write(item)); item.put("id", id); return item;
  }

  @DeleteMapping("/roles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRole(@PathVariable String id) {
    access.require("role:manage"); seedRolesIfNeeded();
    Object raw = redis.opsForHash().get(ROLE_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
    if (Boolean.TRUE.equals(read(raw.toString()).get("builtIn"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "系统角色不可删除");
    for (Object value : redis.opsForHash().values(USER_HASH)) if (id.equals(String.valueOf(read(value.toString()).get("role")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "角色仍被用户使用");
    redis.opsForHash().delete(ROLE_HASH, id);
  }

  @GetMapping("/users")
  public Map<String, Object> users() {
    access.require("org:manage");
    seedAdminIfNeeded();
    List<Map<String, Object>> items = new ArrayList<>();
    redis.opsForHash().entries(USER_HASH).forEach((id, value) -> {
      Map<String, Object> item = read((String) value);
      item.remove("passwordHash");
      item.put("id", id.toString());
      item.put("avatarUrl", avatarUrl(id.toString()));
      items.add(item);
    });
    items.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("displayName", ""))));
    return Map.of("items", items);
  }

  @PostMapping("/users")
  public Map<String, Object> inviteUser(@RequestBody Map<String, Object> request) {
    access.require("user:manage");
    requireText(request, "email");
    String id = UUID.randomUUID().toString();
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("displayName", request.getOrDefault("displayName", String.valueOf(request.get("email")).split("@")[0]));
    item.put("email", request.get("email"));
    item.put("role", request.getOrDefault("role", "developer"));
    item.put("departmentId", request.getOrDefault("departmentId", "engineering"));
    item.put("gender", genderFrom(request.get("gender")));
    item.put("status", "active");
    redis.opsForHash().put(USER_HASH, id, write(item));
    item.put("id", id);
    item.put("avatarUrl", null);
    return item;
  }

  @PutMapping("/users/{id}")
  public Map<String, Object> updateUser(@PathVariable String id, @RequestBody Map<String, Object> request) {
    access.require("user:manage");
    Object raw = redis.opsForHash().get(USER_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    requireText(request, "email");
    Map<String, Object> item = read(raw.toString());
    if (request.containsKey("displayName")) item.put("displayName", request.get("displayName"));
    item.put("email", request.get("email"));
    if (request.containsKey("role")) item.put("role", request.get("role"));
    if (request.containsKey("departmentId")) item.put("departmentId", request.get("departmentId"));
    if (request.containsKey("gender")) item.put("gender", genderFrom(request.get("gender")));
    if (request.containsKey("status") && !"demo-user".equals(id)) item.put("status", request.get("status"));
    redis.opsForHash().put(USER_HASH, id, write(item)); item.remove("passwordHash"); item.put("id", id); item.put("avatarUrl", avatarUrl(id)); return item;
  }

  @PostMapping(value = "/users/{id}/avatar", consumes = "multipart/form-data")
  public Map<String, Object> uploadUserAvatar(@PathVariable String id, @RequestPart("file") MultipartFile file) {
    access.require("user:manage");
    Object raw = redis.opsForHash().get(USER_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    byte[] image = normalizeAvatar(file);
    String object = storage.putImage("avatars/users/" + id + "/" + UUID.randomUUID() + ".png", image, "image/png");
    db.update("insert into cw_user_profile (owner_id, avatar_object) values (?, ?) on conflict (owner_id) do update set avatar_object = excluded.avatar_object, updated_at = now()", id, object);
    Map<String, Object> item = read(raw.toString());
    item.remove("passwordHash"); item.put("id", id); item.put("avatarUrl", storage.imageUrl(object));
    return item;
  }

  @DeleteMapping("/users/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteUser(@PathVariable String id) {
    access.require("user:manage");
    if ("demo-user".equals(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前管理员账号不可删除");
    if (redis.opsForHash().get(USER_HASH, id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    redis.opsForHash().delete(USER_HASH, id);
  }

  @PutMapping("/users/{id}/password")
  public Map<String, Object> resetUserPassword(@PathVariable String id, @RequestBody Map<String, Object> request) {
    access.require("user:manage");
    Object raw = redis.opsForHash().get(USER_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    requireText(request, "password");
    String password = String.valueOf(request.get("password"));
    if (password.length() < 8) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少需要 8 位");
    Map<String, Object> item = read(raw.toString()); item.put("passwordHash", passwordEncoder.encode(password)); item.put("status", "active");
    redis.opsForHash().put(USER_HASH, id, write(item));
    return Map.of("id", id, "status", "active", "message", "密码已重置");
  }

  @GetMapping("/departments")
  public Map<String, Object> departments() {
    access.require("org:manage"); seedDepartmentsIfNeeded();
    List<Map<String, Object>> items = new ArrayList<>();
    redis.opsForHash().entries(DEPARTMENT_HASH).forEach((id, value) -> { Map<String, Object> item = read((String) value); item.put("id", id.toString()); items.add(item); });
    items.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("name", ""))));
    return Map.of("items", items);
  }

  @PostMapping("/departments")
  public Map<String, Object> createDepartment(@RequestBody Map<String, Object> request) {
    access.require("department:manage"); requireText(request, "name"); seedDepartmentsIfNeeded();
    String id = UUID.randomUUID().toString(); Map<String, Object> item = departmentFrom(request);
    redis.opsForHash().put(DEPARTMENT_HASH, id, write(item)); item.put("id", id); return item;
  }

  @PutMapping("/departments/{id}")
  public Map<String, Object> updateDepartment(@PathVariable String id, @RequestBody Map<String, Object> request) {
    access.require("department:manage"); seedDepartmentsIfNeeded(); Object raw = redis.opsForHash().get(DEPARTMENT_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在");
    requireText(request, "name"); Map<String, Object> item = departmentFrom(request);
    redis.opsForHash().put(DEPARTMENT_HASH, id, write(item)); item.put("id", id); return item;
  }

  @DeleteMapping("/departments/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteDepartment(@PathVariable String id) {
    access.require("department:manage"); seedDepartmentsIfNeeded();
    if ("root".equals(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "根组织不可删除");
    Object raw = redis.opsForHash().get(DEPARTMENT_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在");
    for (Object value : redis.opsForHash().values(DEPARTMENT_HASH)) if (id.equals(String.valueOf(read(value.toString()).get("parentId")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "部门仍有下级部门");
    for (Object value : redis.opsForHash().values(USER_HASH)) if (id.equals(String.valueOf(read(value.toString()).get("departmentId")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "部门仍有成员");
    redis.opsForHash().delete(DEPARTMENT_HASH, id);
  }

  @PutMapping("/users/{id}/role")
  public Map<String, Object> updateUserRole(@PathVariable String id, @RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    Object raw = redis.opsForHash().get(USER_HASH, id);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    requireText(request, "role");
    Map<String, Object> item = read(raw.toString());
    item.put("role", request.get("role"));
    redis.opsForHash().put(USER_HASH, id, write(item));
    item.put("id", id);
    return item;
  }

  private String currentUser() { return StpUtil.getLoginIdAsString(); }
  private Map<String, Object> roleFrom(Map<String, Object> request, boolean builtIn) { Map<String, Object> item = new LinkedHashMap<>(); item.put("name", request.get("name")); item.put("description", request.getOrDefault("description", "自定义协作角色")); item.put("permissions", request.getOrDefault("permissions", List.of("读写项目"))); item.put("menus", request.getOrDefault("menus", List.of("overview", "projects"))); item.put("builtIn", builtIn); return item; }
  private Map<String, Object> departmentFrom(Map<String, Object> request) { Map<String, Object> item = new LinkedHashMap<>(); item.put("name", request.get("name")); item.put("parentId", request.getOrDefault("parentId", "root")); item.put("description", request.getOrDefault("description", "")); return item; }
  private void seedDepartmentsIfNeeded() { if (redis.opsForHash().size(DEPARTMENT_HASH) == 0) { redis.opsForHash().put(DEPARTMENT_HASH, "root", write(departmentFrom(Map.of("name", "CodeWeave", "parentId", "")))); redis.opsForHash().put(DEPARTMENT_HASH, "engineering", write(departmentFrom(Map.of("name", "研发部", "parentId", "root")))); redis.opsForHash().put(DEPARTMENT_HASH, "product", write(departmentFrom(Map.of("name", "产品部", "parentId", "root")))); redis.opsForHash().put(DEPARTMENT_HASH, "operations", write(departmentFrom(Map.of("name", "运营部", "parentId", "root")))); } }
  private void seedRolesIfNeeded() { if (redis.opsForHash().size(ROLE_HASH) == 0) { redis.opsForHash().put(ROLE_HASH, "admin", write(roleFrom(Map.of("name", "管理员", "description", "管理组织和全部项目", "permissions", List.of("全部权限"), "menus", List.of("overview", "agent", "projects", "conflicts", "audit", "settings")), true))); redis.opsForHash().put(ROLE_HASH, "developer", write(roleFrom(Map.of("name", "开发成员", "description", "参与已授权项目", "permissions", List.of("读写项目"), "menus", List.of("overview", "agent", "projects", "conflicts")), true))); redis.opsForHash().put(ROLE_HASH, "viewer", write(roleFrom(Map.of("name", "只读协作者", "description", "查看会话与分享内容", "permissions", List.of("仅查看"), "menus", List.of("overview", "projects")), true))); } }
  private String encryptKey(Object apiKey) { return apiKey == null || String.valueOf(apiKey).isBlank() ? "" : crypto.encrypt(String.valueOf(apiKey)); }
  private Map<String, Object> ragflowConfig(String owner) {
    Object raw = redis.opsForValue().get(RAGFLOW_HASH_PREFIX + owner);
    if (raw == null) return new LinkedHashMap<>();
    return read(String.valueOf(raw));
  }
  private String datasetEndpoint(String url) {
    String base = url.trim().replaceAll("/+$", "");
    if (base.endsWith("/datasets")) return base;
    return base.endsWith("/api/v1") ? base + "/datasets" : base + "/api/v1/datasets";
  }
  private Map<String, Object> publicModel(ModelConfigRepository.ModelConfig item) { Map<String, Object> result = new LinkedHashMap<>(); result.put("id", item.id()); result.put("name", item.name()); result.put("endpoint", item.endpoint()); result.put("defaultModel", item.defaultModel()); result.put("updatedAt", item.updatedAt().toString()); result.put("hasApiKey", item.encryptedApiKey() != null && !item.encryptedApiKey().isBlank()); return result; }
  private void seedAdminIfNeeded() { if (redis.opsForHash().size(USER_HASH) == 0) { Map<String, Object> admin = new LinkedHashMap<>(); admin.put("displayName", "Lin"); admin.put("email", "lin@company.com"); admin.put("role", "admin"); admin.put("gender", "male"); admin.put("status", "active"); redis.opsForHash().put(USER_HASH, "demo-user", write(admin)); } }
  private String genderFrom(Object value) { return "female".equals(String.valueOf(value)) ? "female" : "male"; }
  private String avatarUrl(String userId) {
    try {
      var objects = db.queryForList("select avatar_object from cw_user_profile where owner_id = ?", String.class, userId);
      if (objects.isEmpty() || objects.get(0) == null || objects.get(0).isBlank()) return null;
      return storage.imageUrl(objects.get(0));
    } catch (Exception ignored) { return null; }
  }
  private byte[] normalizeAvatar(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择不超过 5 MB 的 PNG 或 JPG 图片");
    try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的 PNG 或 JPG 图片");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 PNG 和 JPG 图片");
        reader.setInput(input);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > 16_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片尺寸过大，请选择较小的图片");
        BufferedImage original = reader.read(0);
        BufferedImage square = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = square.createGraphics();
        try {
          graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
          graphics.drawImage(original, 0, 0, 256, 256, x, y, x + side, y + side, null);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(square, "png", bytes); return bytes.toByteArray();
      } finally { reader.dispose(); }
    } catch (ResponseStatusException error) { throw error; }
    catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片无法读取，请重新选择 PNG 或 JPG 图片"); }
  }
  private void requireText(Map<String, Object> request, String key) { if (request.get(key) == null || String.valueOf(request.get(key)).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空"); }
  private Map<String, Object> read(String value) { try { return mapper.readValue(value, new TypeReference<>() {}); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "设置数据格式错误"); } }
  private String write(Map<String, Object> value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "设置保存失败"); } }
}
