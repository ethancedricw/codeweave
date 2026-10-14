package com.codeweave.auth;
import cn.dev33.satoken.stp.*;
import com.codeweave.auth.AccessControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
@RestController @RequestMapping("/api/auth") public class AuthController {
  private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder; private final AccessControlService access; private final StringRedisTemplate redis; private final ObjectMapper mapper;
  public AuthController(AccessControlService access, StringRedisTemplate redis, ObjectMapper mapper, org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) { this.passwordEncoder = passwordEncoder; this.access = access; this.redis = redis; this.mapper = mapper; }
  public record LoginRequest(@Email @NotBlank String email,@NotBlank String password){}
  public record LoginResponse(String token,String userId,String displayName){}
  @PostMapping("/login") public LoginResponse login(@Valid @RequestBody LoginRequest r){ String userId=null, displayName=null; var entries=redis.opsForHash().entries("cw:settings:users"); for(var entry: entries.entrySet()) try { var user=mapper.readValue(String.valueOf(entry.getValue()), java.util.Map.class); if(r.email().equalsIgnoreCase(String.valueOf(user.getOrDefault("email", "")))) { userId=String.valueOf(entry.getKey()); displayName=String.valueOf(user.getOrDefault("displayName", r.email().split("@")[0])); String expected=String.valueOf(user.getOrDefault("password", "Aa123456")); if(!(user.containsKey("passwordHash") ? passwordEncoder.matches(r.password(), String.valueOf(user.get("passwordHash"))) : r.password().equals(expected))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码错误"); break; } } catch(org.springframework.web.server.ResponseStatusException ex) { throw ex; } catch(Exception ignored) {} if(userId==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码错误"); StpUtil.login(userId); var t=StpUtil.getTokenInfo(); return new LoginResponse(t.getTokenValue(),userId,displayName); }
  public record PasswordRequest(@NotBlank @Size(min=8, max=72) String password) {}
  @PutMapping("/password") public void changePassword(@Valid @RequestBody PasswordRequest request) {
    StpUtil.checkLogin();
    String id = StpUtil.getLoginIdAsString();
    Object raw = redis.opsForHash().get("cw:settings:users", id);
    if (raw == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "用户不存在");
    try {
      var user = mapper.readValue(raw.toString(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
      user.put("passwordHash", passwordEncoder.encode(request.password())); user.remove("password");
      redis.opsForHash().put("cw:settings:users", id, mapper.writeValueAsString(user));
      StpUtil.logout(id);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "密码重置失败"); }
  }
  @PostMapping("/logout") public void logout(){ StpUtil.logout(); }
  @GetMapping("/me") public Object me(){ StpUtil.checkLogin(); var result = new java.util.LinkedHashMap<String,Object>(access.snapshot()); result.put("userId", StpUtil.getLoginIdAsString()); Object raw=redis.opsForHash().get("cw:settings:users", StpUtil.getLoginIdAsString()); if(raw!=null) try { var user=mapper.readValue(String.valueOf(raw), java.util.Map.class); result.put("displayName", user.getOrDefault("displayName", StpUtil.getLoginIdAsString())); result.put("email", user.getOrDefault("email", "")); } catch(Exception ignored) {} return result; }
}

