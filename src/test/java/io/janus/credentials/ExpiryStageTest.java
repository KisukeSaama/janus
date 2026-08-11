package io.janus.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.*;

import org.junit.jupiter.api.Test;

import io.janus.audit.AuditAction;

class ExpiryStageTest {
    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");
    private static final int NOTICE = 30;
    private static final int WARNING = 7;

    private static Instant inDays(double days) {
        return NOW.plus(Duration.ofMinutes((long) (days * 1440)));
    }

    @Test
    void aDeadlineBeyondTheNoticeWindowIsNotWorthSaying() {
        assertThat(ExpiryStage.reached(inDays(31), NOW, NOTICE, WARNING)).isEmpty();
    }

    @Test
    void aDeadlineInsideTheNoticeWindowIsAQuietNotice() {
        assertThat(ExpiryStage.reached(inDays(29), NOW, NOTICE, WARNING)).contains(ExpiryStage.NOTICE);
    }

    @Test
    void aDeadlineInsideTheWarningWindowIsInsistent() {
        assertThat(ExpiryStage.reached(inDays(6), NOW, NOTICE, WARNING)).contains(ExpiryStage.WARNING);
    }

    @Test
    void aPassedDeadlineIsExpired() {
        assertThat(ExpiryStage.reached(inDays(-1), NOW, NOTICE, WARNING)).contains(ExpiryStage.EXPIRED);
    }

    /** The operator asked to be told thirty days ahead, so thirty days ahead is when it is said. */
    @Test
    void aDeadlineExactlyOnAThresholdCounts() {
        assertThat(ExpiryStage.reached(inDays(30), NOW, NOTICE, WARNING)).contains(ExpiryStage.NOTICE);
        assertThat(ExpiryStage.reached(inDays(7), NOW, NOTICE, WARNING)).contains(ExpiryStage.WARNING);
    }

    @Test
    void theInstantOfExpiryIsAlreadyExpired() {
        assertThat(ExpiryStage.reached(NOW, NOW, NOTICE, WARNING)).contains(ExpiryStage.EXPIRED);
    }

    @Test
    void aCredentialWithNoRecordedDeadlineIsNeverAnnounced() {
        assertThat(ExpiryStage.reached(null, NOW, NOTICE, WARNING)).isEmpty();
    }

    /** Rounding down would announce "expires today" on the morning of the day before. */
    @Test
    void aPartDayCountsAsAWholeDayLeft() {
        assertThat(ExpiryStage.daysRemaining(inDays(0.45), NOW)).isEqualTo(1);
        assertThat(ExpiryStage.daysRemaining(inDays(6.5), NOW)).isEqualTo(7);
    }

    @Test
    void daysRunNegativeOnceTheDateHasPassed() {
        assertThat(ExpiryStage.daysRemaining(inDays(-2), NOW)).isEqualTo(-2);
        assertThat(ExpiryStage.daysRemaining(NOW, NOW)).isZero();
    }

    @Test
    void theStagesEscalateInOrderSoAWorseOneCanBeRecognised() {
        assertThat(ExpiryStage.NOTICE).isLessThan(ExpiryStage.WARNING);
        assertThat(ExpiryStage.WARNING).isLessThan(ExpiryStage.EXPIRED);
    }

    @Test
    void eachStageCarriesItsOwnToneAndAuditAction() {
        assertThat(ExpiryStage.NOTICE.severity()).isEqualTo("INFO");
        assertThat(ExpiryStage.WARNING.severity()).isEqualTo("WARN");
        assertThat(ExpiryStage.EXPIRED.severity()).isEqualTo("CRITICAL");
        assertThat(ExpiryStage.NOTICE.auditAction()).isEqualTo(AuditAction.CREDENTIAL_EXPIRY_NOTICE);
        assertThat(ExpiryStage.WARNING.auditAction()).isEqualTo(AuditAction.CREDENTIAL_EXPIRY_WARNING);
        assertThat(ExpiryStage.EXPIRED.auditAction()).isEqualTo(AuditAction.CREDENTIAL_EXPIRED);
    }
}
