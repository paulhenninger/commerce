package com.thirdstone.commerce.platform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * The database is away (outage, failover, pool exhausted): a request gets a clean
 * 503 with Retry-After, which clients may retry, rather than a 500 that reads as
 * a bug. Ahead of the platform's catch-all handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseUnavailableHandler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableHandler.class);

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<Map<String, Object>> databaseAway(Exception e) {
        log.atWarn().log("Database unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(Map.of("message", "Temporarily unavailable; please try again", "code", "UNAVAILABLE"));
    }
}
