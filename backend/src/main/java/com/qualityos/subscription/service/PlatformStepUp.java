package com.qualityos.subscription.service;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The most serious operator actions need the password again, recently. The time is held in the database. */
@Component
public class PlatformStepUp {

    static final int WINDOW_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    PlatformStepUp(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void require() {
        Boolean recent = tx.execute(status ->
                jdbc.queryForObject("SELECT platform_recently_reauthenticated()", Boolean.class));
        if (!Boolean.TRUE.equals(recent)) {
            throw new QualityOsException(ErrorCode.REAUTHENTICATION_REQUIRED, "Enter your password to continue.");
        }
    }
}
