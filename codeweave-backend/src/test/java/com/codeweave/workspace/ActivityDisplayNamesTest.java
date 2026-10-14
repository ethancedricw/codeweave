package com.codeweave.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityDisplayNamesTest {
  @Test
  void resolvesLegacyMemberIdsAndPrefersCurrentDisplayName() {
    var redis = mock(StringRedisTemplate.class);
    var hash = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hash);
    when(hash.get("cw:settings:users", "actor-id")).thenReturn("{\"displayName\":\"Alex\",\"name\":\"Other\"}");
    when(hash.get("cw:settings:users", "member-id")).thenReturn("{\"displayName\":\" \",\"name\":\"Lin\"}");
    var controller = new WorkspaceController(redis, new ObjectMapper(), null, null, null);
    var item = controller.activityDisplayNames(new LinkedHashMap<>(Map.of("actorId", "actor-id", "actor", "old-name", "type", "project.member.added", "target", "member-id")));
    assertEquals("Alex", item.get("actor"));
    assertEquals("Lin", item.get("target"));
    assertEquals("member-id", item.get("targetId"));
    assertEquals("actor-id", item.get("actorId"));
  }

  @Test
  void hidesUnknownUuidAndPreservesProjectNames() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
    var controller = new WorkspaceController(redis, new ObjectMapper(), null, null, null);
    var item = controller.activityDisplayNames(new LinkedHashMap<>(Map.of("actor", "69a28d67-fa25-4e22-ac48-ced59fe5a48f", "type", "project.created", "target", "访谈项目")));
    assertEquals("团队成员", item.get("actor"));
    assertEquals("访谈项目", item.get("target"));
  }
}
