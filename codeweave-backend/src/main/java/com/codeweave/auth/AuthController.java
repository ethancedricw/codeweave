package com.codeweave.auth;
import cn.dev33.satoken.stp.*;
import com.codeweave.auth.AccessControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth") public class AuthController {
  private final AccessControlService access;
  public AuthController(AccessControlService access) { this.access = access; }
  public record LoginRequest(@Email @NotBlank String email,@NotBlank String password){}
  public record LoginResponse(String token,String userId,String displayName){}
  @PostMapping("/login") public LoginResponse login(@Valid @RequestBody LoginRequest r){ StpUtil.login("demo-user"); var t=StpUtil.getTokenInfo(); return new LoginResponse(t.getTokenValue(),"demo-user","Lin"); }
  @PostMapping("/logout") public void logout(){ StpUtil.logout(); }
  @GetMapping("/me") public Object me(){ StpUtil.checkLogin(); var result = new java.util.LinkedHashMap<String,Object>(access.snapshot()); result.put("userId", StpUtil.getLoginIdAsString()); return result; }
}
