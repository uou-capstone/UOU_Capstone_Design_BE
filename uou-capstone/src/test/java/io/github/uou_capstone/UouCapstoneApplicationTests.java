package io.github.uou_capstone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.redisson.api.RedissonClient;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import(UouCapstoneApplicationTests.TestRedisBeans.class)
class UouCapstoneApplicationTests {

	@Test
	void contextLoads() {
	}

	@TestConfiguration
	static class TestRedisBeans {
		@Bean
		RedissonClient redissonClient() {
			return mock(RedissonClient.class);
		}
	}
}
