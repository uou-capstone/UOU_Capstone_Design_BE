package io.github.uou_capstone.aiplatform.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.uou_capstone.aiplatform.domain.material.generation.listener.MaterialGenerationProgressListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 설정
 * 
 * Redis 캐싱을 위한 설정
 * - StringRedisTemplate: 문자열 기반 캐싱
 * - RedisTemplate: 객체 기반 캐싱 (필요시)
 */
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate Bean 등록
     * 문자열 기반 캐싱에 사용
     * 
     * 사용 예시:
     * - Profile JSON 캐싱
     * - 세션 상태 캐싱
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    /**
     * RedisTemplate Bean 등록
     * 객체 기반 캐싱에 사용 (필요시)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    /**
     * RedissonClient Bean 등록
     * 분산 락(Distributed Lock)에 사용.
     * RedisConnectionFactory 대신 properties 사용으로 Redisson 스타터와의 순환 참조 방지.
     */
    @Bean
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        return Redisson.create(config);
    }

    /**
     * RedisMessageListenerContainer Bean 등록
     * Redis Pub/Sub 메시지 리스너에 사용
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MaterialGenerationProgressListener progressListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // Material Generation 진행 상황 채널 구독
        // 패턴: "shared:progress:*" (모든 세션의 진행 상황)
        container.addMessageListener(
            new MessageListenerAdapter(progressListener),
            new PatternTopic("shared:progress:*")
        );
        
        return container;
    }
}
