package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CourseReportCriteriaCatalog {

    private static final List<ReportCriterionResponse> BUILT_IN_CRITERIA = List.of(
            builtIn("CONCEPT_UNDERSTANDING", "개념 이해도", "핵심 개념을 정확히 파악하고 연결해서 이해하는 힘"),
            builtIn("QUESTION_SPECIFICITY", "질문 구체성", "수업 중 질문이 구체적이고 학습 병목을 잘 드러내는 정도"),
            builtIn("PROBLEM_SOLVING", "문제 해결력", "퀴즈와 문항 풀이에서 답을 구성해내는 능력"),
            builtIn("APPLICATION_TRANSFER", "응용전이력", "배운 내용을 새로운 문제나 문맥에 연결하는 능력"),
            builtIn("QUIZ_ACCURACY", "퀴즈 정확도", "시험퀴즈에서 실제 정답률로 드러난 성취도"),
            builtIn("LEARNING_PERSISTENCE", "학습 지속성", "페이지 이동, 누적 세션, 반복 학습에서 보이는 꾸준함"),
            builtIn("WRONG_ANSWER_REFLECTION", "오답 성찰력", "피드백과 약점 메모를 바탕으로 스스로 보완하는 힘"),
            builtIn("CLASS_PARTICIPATION", "수업 참여도", "질문, 응답, 세션 활동량으로 확인되는 참여 수준"),
            builtIn("LEARNING_CONFIDENCE", "학습 자신감", "학습자 모델 confidence와 반응 흐름에서 보이는 자신감"),
            builtIn("GROWTH_MOMENTUM", "성장 모멘텀", "최근 흐름이 좋아지고 있는지, 다음 상승 여지가 있는지")
    );

    public List<ReportCriterionResponse> builtInCriteria() {
        return BUILT_IN_CRITERIA;
    }

    public int builtInCount() {
        return BUILT_IN_CRITERIA.size();
    }

    private static ReportCriterionResponse builtIn(String key, String label, String description) {
        return ReportCriterionResponse.builder()
                .id("builtin:" + key)
                .key(key)
                .criterionId(null)
                .label(label)
                .description(description)
                .weight(0)
                .builtIn(true)
                .editable(false)
                .deletable(false)
                .dataSourceHint(List.of("question_log", "quiz_result", "learning_evidence", "feedback"))
                .fallbackPolicy("INSUFFICIENT_EVIDENCE")
                .build();
    }
}
