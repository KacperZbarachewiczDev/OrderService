package com.task.ing.orderaudit.adapter.in.rest;

import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IssueNotFoundException.class)
    public ProblemDetail handleNotFound(IssueNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Audit issue not found");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(SourceUnavailableException.class)
    public ProblemDetail handleSourceUnavailable(SourceUnavailableException e) {
        log.warn("Source system unavailable: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Source system unavailable");
        problem.setDetail(e.getMessage());
        return problem;
    }
}
