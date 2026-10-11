package com.codeweave.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stores Sa-Token sessions in the same Redis used by the workbench. */
public final class RedisSaTokenDao implements SaTokenDao {
  private static final String PREFIX = "cw:satoken:";
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper = new ObjectMapper();

  public RedisSaTokenDao(StringRedisTemplate redis) { this.redis = redis; }
  private String key(String k) { return PREFIX + k; }
  private long ttl(long timeout) { return timeout <= 0 ? SaTokenDao.NOT_VALUE_EXPIRE : timeout; }

  @Override public String get(String key) { return redis.opsForValue().get(key(key)); }
  @Override public void set(String key, String value, long timeout) {
    if (timeout == SaTokenDao.NEVER_EXPIRE) redis.opsForValue().set(key(key), value);
    else redis.opsForValue().set(key(key), value, Duration.ofSeconds(ttl(timeout)));
  }
  @Override public void update(String key, String value) { redis.opsForValue().set(key(key), value); }
  @Override public void delete(String key) { redis.delete(key(key)); }
  @Override public long getTimeout(String key) { Long value = redis.getExpire(key(key)); return value == null ? SaTokenDao.NOT_VALUE_EXPIRE : value; }
  @Override public void updateTimeout(String key, long timeout) { redis.expire(key(key), Duration.ofSeconds(ttl(timeout))); }
  @Override public Object getObject(String key) {
    String value = get(key); if (value == null) return null;
    try { return mapper.readValue(value, Object.class); } catch (Exception ignored) { return value; }
  }
  @Override public SaSession getSession(String id) {
    Object value = getObject(id); if (value == null) return null;
    if (value instanceof SaSession session) return session;
    try { return mapper.convertValue(value, SaSession.class); } catch (IllegalArgumentException ignored) { return null; }
  }
  @Override public void setObject(String key, Object value, long timeout) {
    try { set(key, mapper.writeValueAsString(value), timeout); } catch (Exception e) { throw new IllegalStateException("Cannot serialize Sa-Token value", e); }
  }
  @Override public void updateObject(String key, Object value) { setObject(key, value, SaTokenDao.NEVER_EXPIRE); }
  @Override public void deleteObject(String key) { delete(key); }
  @Override public long getObjectTimeout(String key) { return getTimeout(key); }
  @Override public void updateObjectTimeout(String key, long timeout) { updateTimeout(key, timeout); }
  @Override public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
    Set<String> keys = redis.keys(PREFIX + prefix + "*"); if (keys == null) return List.of();
    List<String> result = new ArrayList<>(); for (String k : keys) { String raw = k.substring(PREFIX.length()); if (keyword == null || raw.contains(keyword)) result.add(raw); }
    result.sort(sortType ? String::compareTo : String::compareTo); int from = Math.min(start, result.size()); int to = Math.min(from + size, result.size()); return result.subList(from, to);
  }
}
