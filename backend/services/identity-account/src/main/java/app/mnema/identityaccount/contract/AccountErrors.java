package app.mnema.identityaccount.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

@RestControllerAdvice
public class AccountErrors {
    private final ObjectMapper json;

    public AccountErrors(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), problem(status, code));
    }

    @ExceptionHandler(AccountFailure.class)
    public ProblemDetail account(AccountFailure error) {
        return problem(error.status(), error.code());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict() {
        return problem(409, "account_conflict");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ProblemDetail invalid() {
        return problem(400, "invalid_request");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge() {
        return problem(413, "avatar_too_large");
    }

    public static ProblemDetail problem(int status, String code) {
        var result = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), code);
        result.setProperty("code", code);
        return result;
    }
}
