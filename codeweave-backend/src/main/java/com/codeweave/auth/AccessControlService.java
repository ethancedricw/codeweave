package com.codeweave.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class AccessControlService {
  private static final String USER_HASH = "cw:settings:users";
  private static final String ROLE_HASH = "cw:settings:roles";
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public AccessControlService(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }

  public void require(String permission) {
    StpUtil.checkLogin();
    if (!has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有" + permission + "权限");
  }

  public boolean has(String permission) {
    String userId = StpUtil.getLoginIdAsString();
    Object userRaw = redis.opsForHash().get(USER_HASH, userId);
    // The local demo account is the bootstrap administrator until a real user table is connected.
    if (userRaw == null && "demo-user".equals(userId)) return true;
    if (userRaw == null) return false;
    Map<String, Object> user = read(userRaw.toString());
    String roleId = String.valueOf(user.getOrDefault("role", "viewer"));
    Object roleRaw = redis.opsForHash().get(ROLE_HASH, roleId);
    if (roleRaw == null) return "admin".equals(roleId);
    Map<String, Object> role = read(roleRaw.toString());
    if (Boolean.TRUE.equals(role.get("builtIn")) && "admin".equals(roleId)) return true;
    List<?> permissions = (List<?>) role.getOrDefault("permissions", List.of());
    return permissions.stream().map(String::valueOf).anyMatch(value -> permission.equals(value) || permissionLabel(value).equals(permission));
  }

  public Map<String, Object> snapshot() {
    String userId = StpUtil.getLoginIdAsString();
    Object raw = redis.opsForHash().get(USER_HASH, userId);
    if (raw == null && "demo-user".equals(userId)) return Map.of("role", "admin", "permissions", List.of("*"), "menus", allMenus());
    if (raw == null) return Map.of("role", "viewer", "permissions", List.of("project:read"), "menus", List.of("overview", "projects"));
    Map<String, Object> user = read(raw.toString()); String roleId = String.valueOf(user.getOrDefault("role", "viewer"));
    Object roleRaw = redis.opsForHash().get(ROLE_HASH, roleId); Map<String, Object> role = roleRaw == null ? Map.of() : read(roleRaw.toString());
    Object configuredMenus = role.get("menus");
    List<?> menus = configuredMenus instanceof List<?> list && !list.isEmpty() ? list : defaultMenus(roleId);
    List<?> permissions = "admin".equals(roleId) ? List.of("*") : (List<?>) role.getOrDefault("permissions", List.of());
    return Map.of("role", roleId, "permissions", permissions, "menus", menus);
  }

  private List<String> allMenus() { return List.of("overview", "agent", "projects", "conflicts", "audit", "settings"); }
  private List<String> defaultMenus(String roleId) { return switch (roleId) { case "admin" -> allMenus(); case "developer" -> List.of("overview", "agent", "projects", "conflicts"); default -> List.of("overview", "projects"); }; }

  private String permissionLabel(String value) {
    return switch (value) {
      case "全部权限" -> "org:manage";
      case "管理组织" -> "org:manage";
      case "管理角色" -> "role:manage";
      case "管理用户" -> "user:manage";
      case "管理部门" -> "department:manage";
      case "读写项目" -> "project:write";
      case "仅查看" -> "project:read";
      case "执行 Agent" -> "task:create";
      default -> value;
    };
  }

  private Map<String, Object> read(String value) { try { return mapper.readValue(value, new TypeReference<>() {}); } catch (Exception ex) { return Map.of(); } }
}
