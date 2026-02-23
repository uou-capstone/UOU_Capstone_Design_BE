package io.github.uou_capstone.aiplatform.domain.course.lecture.exception;

import org.springframework.http.HttpStatusCode;

public class StreamingApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public StreamingApiException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }
}

