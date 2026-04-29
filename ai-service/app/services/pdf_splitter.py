from ai_agent import PdfAnalysis


def analyze_and_split(pdf_path: str):
    # PdfAnalysis.main은 PDF 경로를 받아 챕터별 (제목, 분할된 PDF 경로) 튜플 리스트를 반환
    output_list = PdfAnalysis.main(pdf_path)

    # API 사용성을 위해 dict 리스트로 변환
    return [
        {"chapter_title": chapter_title, "pdf_path": chapter_pdf_path}
        for chapter_title, chapter_pdf_path in output_list
    ]


