package com.codeweave.workspace;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresenceTest {
  @Test
  void globalHeartbeatReportsMembersOnlineWithoutEnteringAgent() {
    try (var auth = mockStatic(StpUtil.class)) {
      auth.when(StpUtil::getLoginIdAsString).thenReturn("a");
      var redis = mock(StringRedisTemplate.class);
      var hash = mock(HashOperations.class);
      var values = mock(ValueOperations.class);
      when(redis.opsForHash()).thenReturn(hash);
      when(redis.opsForValue()).thenReturn(values);
      when(hash.get("cw:workspace:projects", "p")).thenReturn("{\"members\":[{\"userId\":\"a\"},{\"userId\":\"b\"}]}");
      var controller = new WorkspaceController(redis, new ObjectMapper(), null, null, null);
      controller.heartbeat();
      verify(values).set(eq("cw:online:a"), anyString(), eq(Duration.ofMinutes(3)));
      when(values.get("cw:online:b")).thenReturn("2026-10-12T00:00:00Z");
      var items = (List<Map<String,Object>>) controller.projectPresence("p").get("items");
      assertEquals(2, items.size());
      assertEquals(false, items.get(0).get("online"));
      assertEquals(true, items.get(1).get("online"));
      when(values.get("cw:online:b")).thenReturn(null);
      items = (List<Map<String,Object>>) controller.projectPresence("p").get("items");
      assertEquals(false, items.get(1).get("online"));
    }
  }
}
