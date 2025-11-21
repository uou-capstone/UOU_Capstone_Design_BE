from ai_agent import MainLectureAgent


def generate_markdown(chapter_title: str, pdf_path: str):
    result = MainLectureAgent.main(chapter_title, pdf_path)
    # result 형식: { chapter_title: explanation }
    return result.get(chapter_title, "")


