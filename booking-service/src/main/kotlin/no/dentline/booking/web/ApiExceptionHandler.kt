package no.dentline.booking.web

import no.dentline.booking.domain.DomainConflictException
import no.dentline.booking.domain.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Maps exceptions to RFC 7807 problem responses:
 * 400 validation/malformed input · 404 unknown id · 409 conflict with a machine-readable `code`.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message).apply { title = "Not found" }

    @ExceptionHandler(NoResourceFoundException::class)
    fun noRoute(e: NoResourceFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such endpoint").apply { title = "Not found" }

    @ExceptionHandler(DomainConflictException::class)
    fun conflict(e: DomainConflictException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message).apply {
            title = "Conflict"
            setProperty("code", e.code.name)
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ProblemDetail {
        val errors = e.bindingResult.fieldErrors
            .map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "Invalid value")) }
            .sortedBy { it["field"] }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "${errors.size} field(s) are invalid").apply {
            title = "Validation failed"
            setProperty("errors", errors)
        }
    }

    @ExceptionHandler(
        IllegalArgumentException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    fun badRequest(e: Exception): ProblemDetail {
        val detail = when (e) {
            is HttpMessageNotReadableException -> "Malformed request body"
            is MethodArgumentTypeMismatchException -> "Parameter '${e.name}' has an invalid value"
            else -> e.message ?: "Bad request"
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply { title = "Bad request" }
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        log.error("Unhandled exception", e)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong").apply {
            title = "Internal error"
        }
    }
}
