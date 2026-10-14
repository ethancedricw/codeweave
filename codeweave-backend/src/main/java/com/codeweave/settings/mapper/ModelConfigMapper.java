package com.codeweave.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codeweave.settings.entity.ModelConfigEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfigEntity> {
  @Select("select id, owner_id, name, model_id, endpoint, api_key_ciphertext, default_model, updated_at from cw_model_config where owner_id = #{ownerId} order by updated_at desc")
  @Results(id = "modelConfig", value = @Result(column = "api_key_ciphertext", property = "encryptedApiKey"))
  List<ModelConfigEntity> findAllByOwnerId(@Param("ownerId") String ownerId);

  @Select("select id, owner_id, name, model_id, endpoint, api_key_ciphertext, default_model, updated_at from cw_model_config where owner_id = #{ownerId} and id = #{id}")
  @ResultMap("modelConfig")
  Optional<ModelConfigEntity> findByOwnerIdAndId(@Param("ownerId") String ownerId, @Param("id") String id);

  @Update("update cw_model_config set default_model = false where owner_id = #{ownerId}")
  int clearDefaults(@Param("ownerId") String ownerId);

  @Insert("insert into cw_model_config (id, owner_id, name, model_id, endpoint, api_key_ciphertext, default_model, updated_at) values (#{id}, #{ownerId}, #{name}, #{modelId}, #{endpoint}, #{encryptedApiKey}, #{defaultModel}, #{updatedAt}) on conflict (id) do update set name = excluded.name, model_id = excluded.model_id, endpoint = excluded.endpoint, api_key_ciphertext = excluded.api_key_ciphertext, default_model = excluded.default_model, updated_at = excluded.updated_at")
  int upsert(ModelConfigEntity model);

  @Delete("delete from cw_model_config where owner_id = #{ownerId} and id = #{id}")
  int deleteByOwnerIdAndId(@Param("ownerId") String ownerId, @Param("id") String id);
}
