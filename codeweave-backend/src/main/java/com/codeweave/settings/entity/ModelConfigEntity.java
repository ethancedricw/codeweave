package com.codeweave.settings.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.sql.Timestamp;

@TableName("cw_model_config")
public class ModelConfigEntity {
  @TableId
  private String id;
  private String ownerId;
  private String name;
  private String modelId;
  private String endpoint;
  @TableField("api_key_ciphertext")
  private String encryptedApiKey;
  private boolean defaultModel;
  private Timestamp updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getOwnerId() { return ownerId; }
  public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getModelId() { return modelId; }
  public void setModelId(String modelId) { this.modelId = modelId; }
  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
  public String getEncryptedApiKey() { return encryptedApiKey; }
  public void setEncryptedApiKey(String encryptedApiKey) { this.encryptedApiKey = encryptedApiKey; }
  public boolean isDefaultModel() { return defaultModel; }
  public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }
  public Timestamp getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
