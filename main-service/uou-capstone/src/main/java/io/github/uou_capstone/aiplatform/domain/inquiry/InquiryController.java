package io.github.uou_capstone.aiplatform.domain.inquiry;

import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryRequestDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "학생 Q&A (손들기) API", description = "학생이 AI에게 질문하고 답변을 받습니다.")
@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "AI에게 질문하기", description = "특정 강의 내용에 대해 AI에게 질문하고 즉시 답변을 받습니다.")
    @PostMapping("/ask")
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<InquiryResponseDto> askQuestion(@RequestBody InquiryRequestDto requestDto) {
        InquiryResponseDto response = inquiryService.askQuestion(requestDto);
        return ResponseEntity.ok(response);
    }
}