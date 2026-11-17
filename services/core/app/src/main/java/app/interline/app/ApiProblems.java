package app.interline.app;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** RFC 9457 problem responses with a stable machine-readable {@code code}. Details never carry secrets. */
final class ApiProblems {
    private ApiProblems() { }

    static ErrorResponseException of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    static ErrorResponseException badRequest(String detail) {
        return of(HttpStatus.BAD_REQUEST, "invalid_request", detail);
    }

    static ErrorResponseException notFound(String what) {
        return of(HttpStatus.NOT_FOUND, "not_found", "Unknown " + what);
    }
}
