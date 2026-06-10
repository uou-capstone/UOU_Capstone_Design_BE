package io.github.uou_capstone.aiplatform.service;

import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock
    private UserRepository userRepository;

    private CurrentUserResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CurrentUserResolver(userRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u@x", "")
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getUserId_queriesOnlyUserId_whenUserIsNotCached() {
        when(userRepository.findIdByEmail("u@x")).thenReturn(Optional.of(7L));

        assertThat(resolver.getUserId()).isEqualTo(7L);

        verify(userRepository, never()).findByEmailWithRoles("u@x");
    }

    @Test
    void getUserId_usesCachedUser_whenUserWasAlreadyLoaded() {
        User user = User.builder().email("u@x").password("p").fullName("u").build();
        ReflectionTestUtils.setField(user, "id", 7L);
        when(userRepository.findByEmailWithRoles("u@x")).thenReturn(Optional.of(user));

        resolver.getUser();

        assertThat(resolver.getUserId()).isEqualTo(7L);
        verify(userRepository, never()).findIdByEmail("u@x");
    }
}
