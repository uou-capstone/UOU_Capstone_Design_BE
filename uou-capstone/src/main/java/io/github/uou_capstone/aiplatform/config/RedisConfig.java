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
     * 분산 락(Distributed Lock)에 사용
     */
    @Bean
    public RedissonClient redissonClient(RedisConnectionFactory connectionFactory) {
        Config config = new Config();
        
        // Redis 연결 정보 가져오기
        if (connectionFactory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) {
            org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuceFactory = 
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) connectionFactory;
            String host = lettuceFactory.getHostName();
            int port = lettuceFactory.getPort();
            int database = lettuceFactory.getDatabase();
            
            config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        } else {
            // 기본값 (localhost:6379)
            config.useSingleServer()
                .setAddress("redis://localhost:6379");
        }
        
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
        // 패턴: "progress:session:*" (모든 세션의 진행 상황)
        container.addMessageListener(
            new MessageListenerAdapter(progressListener),
            new PatternTopic("progress:session:*")
        );
        
        return container;
    }
}
