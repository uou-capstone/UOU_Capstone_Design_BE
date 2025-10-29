package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureDetailResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "강의 API", description = "강의 생성 등 강의 관련 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LectureController {

    private final LectureService lectureService;

    @Operation(summary = "강의 생성", description = "특정 과목에 새로운 강의를 생성합니다.")
    @PostMapping("/courses/{courseId}/lectures")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<LectureResponseDto> createLecture(
            @PathVariable Long courseId,
            @RequestBody LectureCreateRequestDto requestDto) {

        Lecture newLecture = lectureService.createLecture(courseId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new LectureResponseDto(newLecture));
    }

    @Operation(summary = "강의 상세 조회", description = "특정 강의의 상세 정보와 AI 생성 콘텐츠 목록을 조회합니다.")
    @GetMapping("/lectures/{lectureId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<LectureDetailResponseDto> getLectureDetail(@PathVariable Long lectureId) {
        LectureDetailResponseDto lectureDetail = lectureService.getLectureDetail(lectureId);
        return ResponseEntity.ok(lectureDetail);
    }
}