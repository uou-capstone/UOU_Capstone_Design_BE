package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service;

import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository.StudentReportAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentReportAnalysisInvalidationService {

    private final StudentReportAnalysisRepository repository;

    @Autowired
    @Lazy
    private StudentReportAnalysisInvalidationService self;

    public void invalidateCourse(Long courseId, String reason) {
        if (courseId == null) {
            return;
        }
        scheduleAfterCommit(() -> self.deleteCourseAnalyses(courseId, reason));
    }

    public void invalidateStudent(Long courseId, Long studentId, String reason) {
        if (courseId == null || studentId == null) {
            return;
        }
        scheduleAfterCommit(() -> self.deleteStudentAnalysis(courseId, studentId, reason));
    }

    private void scheduleAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSafely(action);
                }
            });
            return;
        }
        runSafely(action);
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Student report analysis invalidation failed.", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteCourseAnalyses(Long courseId, String reason) {
        int deleted = repository.deleteByCourseId(courseId);
        log.info("Student report analyses invalidated: courseId={}, deleted={}, reason={}",
                courseId, deleted, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteStudentAnalysis(Long courseId, Long studentId, String reason) {
        int deleted = repository.deleteByCourseIdAndStudentId(courseId, studentId);
        log.info("Student report analysis invalidated: courseId={}, studentId={}, deleted={}, reason={}",
                courseId, studentId, deleted, reason);
    }
}
