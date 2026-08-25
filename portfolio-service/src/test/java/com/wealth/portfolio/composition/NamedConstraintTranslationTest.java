package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

class NamedConstraintTranslationTest {

    @Test
    void matchesHibernateConstraintNameExactly() {
        ConstraintViolationException hibernate =
                new ConstraintViolationException(
                        "duplicate key", null, "uq_portfolios_user_id");
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("integrity", hibernate);

        assertThat(
                        HoldingReplacementService.isNamedConstraint(
                                wrapped, HoldingReplacementService.UQ_PORTFOLIOS_USER_ID))
                .isTrue();
    }

    @Test
    void matchesPostgresServerErrorConstraintEvenWhenQualified() {
        ServerErrorMessage serverError = mock(ServerErrorMessage.class);
        when(serverError.getConstraint()).thenReturn("public.uq_portfolios_user_id");
        PSQLException psql = mock(PSQLException.class);
        when(psql.getServerErrorMessage()).thenReturn(serverError);
        when(psql.getMessage()).thenReturn("ERROR: duplicate key value violates unique constraint");

        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("integrity", psql);

        assertThat(
                        HoldingReplacementService.isNamedConstraint(
                                wrapped, HoldingReplacementService.UQ_PORTFOLIOS_USER_ID))
                .isTrue();
    }

    @Test
    void doesNotTranslateUnrelatedConstraintEvenWhenMessageMentionsTargetName() {
        ServerErrorMessage serverError = mock(ServerErrorMessage.class);
        when(serverError.getConstraint()).thenReturn("chk_asset_holdings_quantity_positive");
        PSQLException psql = mock(PSQLException.class);
        when(psql.getServerErrorMessage()).thenReturn(serverError);
        when(psql.getMessage())
                .thenReturn(
                        "ERROR: new row violates check constraint"
                                + " (also mentions uq_portfolios_user_id in prose)");

        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("integrity", psql);

        assertThat(
                        HoldingReplacementService.isNamedConstraint(
                                wrapped, HoldingReplacementService.UQ_PORTFOLIOS_USER_ID))
                .isFalse();
    }

    @Test
    void messageOnlyMentionWithoutStructuredNameIsNotAMatch() {
        PSQLException psql = mock(PSQLException.class);
        when(psql.getServerErrorMessage()).thenReturn(null);
        when(psql.getMessage()).thenReturn("something about uq_portfolios_user_id");

        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("integrity", psql);

        assertThat(
                        HoldingReplacementService.isNamedConstraint(
                                wrapped, HoldingReplacementService.UQ_PORTFOLIOS_USER_ID))
                .isFalse();
    }
}
