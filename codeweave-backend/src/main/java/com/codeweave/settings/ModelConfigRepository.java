package com.codeweave.settings;

import com.codeweave.settings.entity.ModelConfigEntity;
import com.codeweave.settings.mapper.ModelConfigMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ModelConfigRepository {
  public record ModelConfig(String id, String ownerId, String name, String modelId, String endpoint, String encryptedApiKey, boolean defaultModel, Instant updatedAt) {
    /** Compatibility for configurations created before display name/model ID were split. */
    public ModelConfig(String id, String ownerId, String name, String endpoint, String encryptedApiKey, boolean defaultModel, Instant updatedAt) {
      this(id, ownerId, name, name, endpoint, encryptedApiKey, defaultModel, updatedAt);
    }
  }

  private final ModelConfigMapper mapper;

  public ModelConfigRepository(ModelConfigMapper mapper) { this.mapper = mapper; }

  public List<ModelConfig> findAll(String ownerId) {
    return mapper.findAllByOwnerId(ownerId).stream().map(this::toModel).toList();
  }

  public Optional<ModelConfig> find(String ownerId, String id) {
    return mapper.findByOwnerIdAndId(ownerId, id).map(this::toModel);
  }

  public void clearDefaults(String ownerId) { mapper.clearDefaults(ownerId); }

  public void save(ModelConfig model) {
    mapper.upsert(toEntity(model));
  }

  public void delete(String ownerId, String id) { mapper.deleteByOwnerIdAndId(ownerId, id); }

  private ModelConfigEntity toEntity(ModelConfig model) {
    ModelConfigEntity entity = new ModelConfigEntity();
    entity.setId(model.id());
    entity.setOwnerId(model.ownerId());
    entity.setName(model.name());
    entity.setModelId(model.modelId());
    entity.setEndpoint(model.endpoint());
    entity.setEncryptedApiKey(model.encryptedApiKey());
    entity.setDefaultModel(model.defaultModel());
    entity.setUpdatedAt(Timestamp.from(model.updatedAt()));
    return entity;
  }

  private ModelConfig toModel(ModelConfigEntity entity) {
    String modelId = java.util.Objects.requireNonNullElse(entity.getModelId(), "").trim();
    if (modelId.isBlank()) modelId = entity.getName();
    return new ModelConfig(entity.getId(), entity.getOwnerId(), entity.getName(), modelId, entity.getEndpoint(), java.util.Objects.requireNonNullElse(entity.getEncryptedApiKey(), ""), entity.isDefaultModel(), entity.getUpdatedAt().toInstant());
  }
}
