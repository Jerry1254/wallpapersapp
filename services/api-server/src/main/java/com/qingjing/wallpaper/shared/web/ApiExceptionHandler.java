package com.qingjing.wallpaper.shared.web;

import com.qingjing.wallpaper.asset.application.AssetValidationException;
import com.qingjing.wallpaper.asset.application.FileStorageException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorEnvelope> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorEnvelope> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiException.ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toDetail)
                .limit(100)
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "The request contains invalid fields",
                details,
                request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorEnvelope> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "The request could not be parsed",
                List.of(),
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorEnvelope> handleMaximumUpload(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "The uploaded file exceeds the server limit",
                List.of(),
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorEnvelope> handleMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "The request media type is not supported",
                List.of(),
                request);
    }

    @ExceptionHandler(FileStorageException.class)
    ResponseEntity<ErrorEnvelope> handleStorage(FileStorageException exception, HttpServletRequest request) {
        HttpStatus status = exception.code() == FileStorageException.Code.FILE_TOO_LARGE
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : exception.code() == FileStorageException.Code.FILE_NOT_FOUND
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.INTERNAL_SERVER_ERROR;
        String code = status == HttpStatus.PAYLOAD_TOO_LARGE
                ? "PAYLOAD_TOO_LARGE"
                : status == HttpStatus.NOT_FOUND ? "ASSET_NOT_FOUND" : "INTERNAL_ERROR";
        if (status.is5xxServerError()) {
            LOGGER.error("Storage failure requestId={} code={}", requestId(request), exception.code(), exception);
        }
        return response(status, code, exception.getMessage(), List.of(), request);
    }

    @ExceptionHandler(AssetValidationException.class)
    ResponseEntity<ErrorEnvelope> handleAssetValidation(
            AssetValidationException exception,
            HttpServletRequest request) {
        boolean unsupported = exception.code() == AssetValidationException.Code.UNSUPPORTED_FILE_TYPE
                || exception.code() == AssetValidationException.Code.DECLARED_TYPE_MISMATCH;
        HttpStatus status = unsupported ? HttpStatus.UNSUPPORTED_MEDIA_TYPE : HttpStatus.UNPROCESSABLE_ENTITY;
        return response(
                status,
                unsupported ? "UNSUPPORTED_MEDIA_TYPE" : "ASSET_VALIDATION_FAILED",
                exception.getMessage(),
                List.of(),
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorEnvelope> handleIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "STATE_CONFLICT",
                "The requested change conflicts with existing data",
                List.of(),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled API failure requestId={}", requestId(request), exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The server could not complete the request",
                List.of(),
                request);
    }

    private ApiException.ErrorDetail toDetail(FieldError fieldError) {
        String message = fieldError.getDefaultMessage() == null ? "is invalid" : fieldError.getDefaultMessage();
        return new ApiException.ErrorDetail(fieldError.getField(), message);
    }

    private ResponseEntity<ErrorEnvelope> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiException.ErrorDetail> details,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorEnvelope(new ErrorBody(
                        code,
                        message,
                        requestId(request),
                        details.isEmpty() ? null : details)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestAttributes.REQUEST_ID);
        return value == null ? java.util.UUID.randomUUID().toString() : value.toString();
    }

    public record ErrorEnvelope(ErrorBody error) {
    }

    public record ErrorBody(
            String code,
            String message,
            String requestId,
            List<ApiException.ErrorDetail> details) {
    }
}
