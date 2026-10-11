package com.codeweave.storage;

import com.codeweave.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MinioStorageService {
  private final MinioProperties properties;
  private final MinioClient client;
  private final ConcurrentHashMap<String, Boolean> readyBuckets = new ConcurrentHashMap<>();

  public MinioStorageService(MinioProperties properties) {
    this.properties = properties;
    this.client = MinioClient.builder()
      .endpoint(properties.endpoint())
      .credentials(properties.accessKey(), properties.secretKey())
      .build();
  }

  public StoredObject upload(String prefix, MultipartFile file) {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
    ensureBucket();
    String originalName = file.getOriginalFilename() == null ? "snapshot.bin" : file.getOriginalFilename();
    String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
    String objectName = prefix.replaceAll("[^a-zA-Z0-9/_-]", "_") + "/" + UUID.randomUUID() + "-" + safeName;
    try {
      client.putObject(PutObjectArgs.builder()
        .bucket(properties.bucket())
        .object(objectName)
        .stream(file.getInputStream(), file.getSize(), -1)
        .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
        .build());
      String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
        .method(Method.GET).bucket(properties.bucket()).object(objectName)
        .expiry(properties.presignedUrlExpirySeconds()).build());
      return new StoredObject(objectName, originalName, file.getContentType(), file.getSize(), url);
    } catch (Exception error) {
      throw new IllegalStateException("MinIO 文件上传失败：" + error.getMessage(), error);
    }
  }

  private void ensureBucket() {
    if (readyBuckets.containsKey(properties.bucket())) return;
    try {
      boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
      if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
      readyBuckets.put(properties.bucket(), true);
    } catch (Exception error) {
      throw new IllegalStateException("MinIO 存储不可用：" + error.getMessage(), error);
    }
  }

  public String putImage(String objectName, byte[] bytes, String contentType) {
    ensureBucket();
    try (var stream = new java.io.ByteArrayInputStream(bytes)) {
      client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(objectName)
        .stream(stream, bytes.length, -1).contentType(contentType).build());
      return objectName;
    } catch (Exception error) { throw new IllegalStateException("Avatar storage is unavailable", error); }
  }

  public synchronized void ensureDefaultImage(String objectName, byte[] bytes) {
    ensureBucket();
    try {
      client.statObject(io.minio.StatObjectArgs.builder().bucket(properties.bucket()).object(objectName).build());
    } catch (io.minio.errors.ErrorResponseException error) {
      if (!"NoSuchKey".equals(error.errorResponse().code())) throw new IllegalStateException("Avatar storage is unavailable", error);
      putImage(objectName, bytes, "image/svg+xml");
    } catch (Exception error) { throw new IllegalStateException("Avatar storage is unavailable", error); }
  }

  public String imageUrl(String objectName) {
    try {
      return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
        .bucket(properties.bucket()).object(objectName).expiry(properties.presignedUrlExpirySeconds()).build());
    } catch (Exception error) { throw new IllegalStateException("Avatar URL is unavailable", error); }
  }

  public record StoredObject(String objectName, String originalName, String contentType, long size, String downloadUrl) {}
}
