package com.codeweave.config;

import cn.dev33.satoken.dao.SaTokenDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisSaTokenConfig {
  @Bean
  public SaTokenDao saTokenDao(StringRedisTemplate redis) {
    return new RedisSaTokenDao(redis);
  }
}
