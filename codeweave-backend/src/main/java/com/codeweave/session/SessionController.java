package com.codeweave.session;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant; import java.util.*;
@RestController @RequestMapping("/api/sessions") public class SessionController {
  private static final String SESSION_HASH = "cw:workspace:sessions";
  private final StringRedisTemplate redis; private final ObjectMapper mapper;
  public SessionController(StringRedisTemplate r, ObjectMapper m){redis=r; mapper=m;}
  @GetMapping public Map<String,Object> list(){
    StpUtil.checkLogin();
    List<Map<String,Object>> items = new ArrayList<>();
    redis.opsForHash().values(SESSION_HASH).forEach(raw -> {
      try {
        Map<String, Object> item = mapper.readValue(String.valueOf(raw), new TypeReference<Map<String,Object>>() {});
        if (sessionVisibleToCurrentUser(item)) items.add(item);
      }
      catch (Exception ignored) { }
    });
    items.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updatedAt", "")), Comparator.reverseOrder()));
    return Map.of("items", items.stream().limit(50).toList());
  }

  /** A project session is visible to every member of that project, while an
   * unassigned session remains private to its owner. */
  private boolean sessionVisibleToCurrentUser(Map<String, Object> session) {
    String userId = StpUtil.getLoginIdAsString();
    if (userId.equals(String.valueOf(session.getOrDefault("ownerId", "")))) return true;
    String projectId = String.valueOf(session.getOrDefault("projectId", "")).trim();
    if (projectId.isBlank()) return false;
    Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (raw == null) return false;
    try {
      Map<String, Object> project = mapper.readValue(String.valueOf(raw), new TypeReference<Map<String,Object>>() {});
      Object membersRaw = project.get("members");
      if (membersRaw instanceof Collection<?> members) {
        for (Object member : members) {
          if (member instanceof Map<?, ?> row && userId.equals(String.valueOf(row.get("userId")))) return true;
        }
      }
      return userId.equals(String.valueOf(project.getOrDefault("ownerId", "")));
    } catch (Exception ignored) {
      return false;
    }
  }
  @PostMapping("/{id}/events") public Map<String,Object> append(@PathVariable String id,@RequestBody Map<String,Object> event){ StpUtil.checkLogin(); String eid=UUID.randomUUID().toString(); redis.opsForStream().add("cw:session:events",Map.of("eventId",eid,"sessionId",id,"createdAt",Instant.now().toString(),"payload",event.toString())); return Map.of("eventId",eid,"sessionId",id); }
}
