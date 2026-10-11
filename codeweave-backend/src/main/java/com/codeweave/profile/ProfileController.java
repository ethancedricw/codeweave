package com.codeweave.profile;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeweave.storage.MinioStorageService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
  private final MinioStorageService storage;
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public ProfileController(MinioStorageService storage, JdbcTemplate db, StringRedisTemplate redis, ObjectMapper mapper) { this.storage = storage; this.db = db; this.redis = redis; this.mapper = mapper; }

  @GetMapping
  public Map<String, Object> profile() {
    StpUtil.checkLogin();
    try {
      for (String kind : new String[]{"user", "assistant"}) {
        try (var stream = new ClassPathResource("avatars/" + kind + ".svg").getInputStream()) {
          storage.ensureDefaultImage("avatars/default/" + kind + ".svg", stream.readAllBytes());
        }
      }
      var objects = db.queryForList("select avatar_object from cw_user_profile where owner_id = ?", String.class, StpUtil.getLoginIdAsString());
      String object = objects.isEmpty() ? "avatars/default/user.svg" : objects.get(0);
      return Map.of("avatarUrl", storage.imageUrl(object), "assistantAvatarUrl", storage.imageUrl("avatars/default/assistant.svg"), "gender", currentGender(), "hasCustomAvatar", !objects.isEmpty());
    } catch (Exception error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "头像存储暂时不可用，请稍后重试"); }
  }

  private String currentGender() {
    Object raw = redis.opsForHash().get("cw:settings:users", StpUtil.getLoginIdAsString());
    if (raw == null) return "male";
    try {
      Map<String, Object> user = mapper.readValue(raw.toString(), new TypeReference<>() {});
      return "female".equals(String.valueOf(user.get("gender"))) ? "female" : "male";
    } catch (Exception ignored) { return "male"; }
  }

  @PostMapping(value = "/avatar", consumes = "multipart/form-data")
  public Map<String, Object> upload(@RequestPart("file") MultipartFile file) {
    StpUtil.checkLogin();
    byte[] image = normalizeAvatar(file);
    String object = storage.putImage("avatars/users/" + UUID.randomUUID() + ".png", image, "image/png");
    db.update("insert into cw_user_profile (owner_id, avatar_object) values (?, ?) on conflict (owner_id) do update set avatar_object = excluded.avatar_object, updated_at = now()",
      StpUtil.getLoginIdAsString(), object);
    return profile();
  }

  static byte[] normalizeAvatar(MultipartFile file) {
    if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw invalid("请选择不超过 5 MB 的 PNG 或 JPG 图片");
    try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw invalid("请选择有效的 PNG 或 JPG 图片");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalid("仅支持 PNG 和 JPG 图片");
        reader.setInput(input);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > 16_000_000) throw invalid("图片尺寸过大，请选择较小的图片");
        BufferedImage original = reader.read(0);
        BufferedImage square = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = square.createGraphics();
        try {
          graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
          graphics.drawImage(original, 0, 0, 256, 256, x, y, x + side, y + side, null);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(square, "png", bytes);
        return bytes.toByteArray();
      } finally { reader.dispose(); }
    } catch (ResponseStatusException error) { throw error; }
    catch (Exception error) { throw invalid("图片无法读取，请重新选择 PNG 或 JPG 图片"); }
  }

  private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
