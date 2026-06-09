package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service;

import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository.StudentReportAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StudentReportAnalysisInvalidationServiceTest {

    @Mock
    private StudentReportAnalysisRepository repository;

    private StudentReportAnalysisInvalidationService service;

    @BeforeEach
    void setUp() {
        service = new StudentReportAnalysisInvalidationService(repository);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void invalidateStudentDeletesImmediatelyWithoutTransaction() {
        service.invalidateStudent(1L, 2L, "test");

        verify(repository).deleteByCourseIdAndStudentId(1L, 2L);
    }

    @Test
    void invalidateCourseRunsDeleteAfterCommitWhenTransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.invalidateCourse(1L, "test");

        verify(repository, never()).deleteByCourseId(1L);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(repository).deleteByCourseId(1L);
    }
}
