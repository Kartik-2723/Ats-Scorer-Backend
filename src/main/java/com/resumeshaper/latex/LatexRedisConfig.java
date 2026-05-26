package com.resumeshaper.latex;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Dedicated RedisTemplate<String, byte[]> for raw PDF byte storage.
 *
 * Your existing RedisTemplate (if any) likely uses String/String or
 * String/Object serialization — we need a byte[] value serializer
 * so PDFs aren't corrupted by JSON encoding.
 */
@Configuration
public class LatexRedisConfig {

    @Bean(name = "latexRedisTemplate")
    public RedisTemplate<String, byte[]> latexRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }
}