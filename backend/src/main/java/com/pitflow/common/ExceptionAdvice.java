package com.pitflow.common;

import java.util.LinkedHashMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ExceptionAdvice {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> business(ApiException ex) {
    return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
    var fields = new LinkedHashMap<String, String>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
    return ResponseEntity.badRequest().body(new ApiError("입력 내용을 확인해 주세요.", fields));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError("이미 등록된 정보이거나 다른 데이터에서 사용 중입니다."));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiError> malformed(Exception ex) {
    return ResponseEntity.badRequest().body(new ApiError("올바른 형식으로 요청해 주세요."));
  }
}
