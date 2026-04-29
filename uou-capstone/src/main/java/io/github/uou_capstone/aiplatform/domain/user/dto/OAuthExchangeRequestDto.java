package io.github.uou_capstone.aiplatform.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OAuthExchangeRequestDto {

    @NotBlank(message = "code 는 필수입니다.")
    private String code;
}
