package io.github.uou_capstone.aiplatform.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PDF 텍스트 추출 유틸리티 클래스
 * 
 * 주요 기능:
 * 1. PDF 파일에서 텍스트 추출
 * 2. 파일 경로 또는 InputStream을 통한 추출
 * 3. 특정 페이지 범위 추출
 * 
 * 사용 예시:
 * - 강의 자료 생성 시 PDF 내용 분석
 * - 시험 생성 시 PDF 기반 문제 생성
 */
@Slf4j
public class PdfTextExtractor {

    /**
     * PDF 파일에서 전체 텍스트 추출
     * 
     * @param filePath PDF 파일 경로
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractText(String filePath) throws IOException {
        return extractText(filePath, 1, Integer.MAX_VALUE);
    }

    /**
     * PDF 파일에서 특정 페이지 범위의 텍스트 추출
     * 
     * @param filePath PDF 파일 경로
     * @param startPage 시작 페이지 (1부터 시작)
     * @param endPage 종료 페이지 (포함)
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractText(String filePath, int startPage, int endPage) throws IOException {
        Path path = Paths.get(filePath);
        
        if (!Files.exists(path)) {
            throw new IOException("PDF 파일을 찾을 수 없습니다: " + filePath);
        }

        if (!Files.isRegularFile(path)) {
            throw new IOException("파일이 아닙니다: " + filePath);
        }

        try (FileInputStream fileInputStream = new FileInputStream(path.toFile());
             PDDocument document = Loader.loadPDF(fileInputStream.readAllBytes())) {
            
            return extractTextFromDocument(document, startPage, endPage);
        }
    }

    /**
     * InputStream에서 PDF 텍스트 추출
     * 
     * @param inputStream PDF 파일 InputStream
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractText(InputStream inputStream) throws IOException {
        return extractText(inputStream, 1, Integer.MAX_VALUE);
    }

    /**
     * InputStream에서 특정 페이지 범위의 텍스트 추출
     * 
     * @param inputStream PDF 파일 InputStream
     * @param startPage 시작 페이지 (1부터 시작)
     * @param endPage 종료 페이지 (포함)
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractText(InputStream inputStream, int startPage, int endPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            return extractTextFromDocument(document, startPage, endPage);
        }
    }

    /**
     * File 객체에서 PDF 텍스트 추출
     * 
     * @param file PDF 파일 객체
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractText(File file) throws IOException {
        return extractText(file, 1, Integer.MAX_VALUE);
    }

    /**
     * File 객체에서 특정 페이지 범위의 텍스트 추출
     * 
     * @param file PDF 파일 객체
     * @param startPage 시작 페이지 (1부터 시작)
     * @param endPage 종료 페이지 (포함)
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractText(File file, int startPage, int endPage) throws IOException {
        if (!file.exists()) {
            throw new IOException("PDF 파일을 찾을 수 없습니다: " + file.getAbsolutePath());
        }

        if (!file.isFile()) {
            throw new IOException("파일이 아닙니다: " + file.getAbsolutePath());
        }

        try (FileInputStream fileInputStream = new FileInputStream(file);
             PDDocument document = Loader.loadPDF(fileInputStream.readAllBytes())) {
            
            return extractTextFromDocument(document, startPage, endPage);
        }
    }

    /**
     * PDDocument에서 텍스트 추출 (내부 메서드)
     * 
     * @param document PDDocument 객체
     * @param startPage 시작 페이지 (1부터 시작)
     * @param endPage 종료 페이지 (포함)
     * @return 추출된 텍스트
     * @throws IOException 텍스트 추출 실패 시
     */
    private static String extractTextFromDocument(PDDocument document, int startPage, int endPage) throws IOException {
        int totalPages = document.getNumberOfPages();
        
        // 페이지 범위 검증
        if (startPage < 1) {
            startPage = 1;
        }
        
        if (endPage > totalPages) {
            endPage = totalPages;
        }
        
        if (startPage > endPage) {
            throw new IllegalArgumentException(
                    String.format("시작 페이지(%d)가 종료 페이지(%d)보다 큽니다.", startPage, endPage)
            );
        }

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(startPage);
        stripper.setEndPage(endPage);
        
        String text = stripper.getText(document);
        
        log.debug("PDF 텍스트 추출 완료: 총 페이지={}, 추출 범위={}-{}, 텍스트 길이={}", 
                totalPages, startPage, endPage, text.length());
        
        return text;
    }

    /**
     * PDF 파일의 총 페이지 수 조회
     * 
     * @param filePath PDF 파일 경로
     * @return 총 페이지 수
     * @throws IOException 파일 읽기 실패 시
     */
    public static int getPageCount(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        
        if (!Files.exists(path)) {
            throw new IOException("PDF 파일을 찾을 수 없습니다: " + filePath);
        }

        try (FileInputStream fileInputStream = new FileInputStream(path.toFile());
             PDDocument document = Loader.loadPDF(fileInputStream.readAllBytes())) {
            
            return document.getNumberOfPages();
        }
    }

    /**
     * PDF 파일의 총 페이지 수 조회 (File 객체)
     * 
     * @param file PDF 파일 객체
     * @return 총 페이지 수
     * @throws IOException 파일 읽기 실패 시
     */
    public static int getPageCount(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("PDF 파일을 찾을 수 없습니다: " + file.getAbsolutePath());
        }

        try (FileInputStream fileInputStream = new FileInputStream(file);
             PDDocument document = Loader.loadPDF(fileInputStream.readAllBytes())) {
            
            return document.getNumberOfPages();
        }
    }

    /**
     * PDF 파일의 특정 페이지 텍스트 추출
     * 
     * @param filePath PDF 파일 경로
     * @param pageNumber 페이지 번호 (1부터 시작)
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractPageText(String filePath, int pageNumber) throws IOException {
        return extractText(filePath, pageNumber, pageNumber);
    }

    /**
     * PDF 파일의 특정 페이지 텍스트 추출 (File 객체)
     * 
     * @param file PDF 파일 객체
     * @param pageNumber 페이지 번호 (1부터 시작)
     * @return 추출된 텍스트
     * @throws IOException 파일 읽기 실패 시
     */
    public static String extractPageText(File file, int pageNumber) throws IOException {
        return extractText(file, pageNumber, pageNumber);
    }
}
