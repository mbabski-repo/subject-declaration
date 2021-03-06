package pl.mbab.subjectdeclaration.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class FieldCoursesException extends RuntimeException {

    public FieldCoursesException(String message) {
        super(message);
    }
}
