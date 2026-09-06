package domain.triage;

import domain.emergency.Severity;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.triage.injury.BodyPartCategory;
import domain.triage.injury.InjuryTriage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

//unit tests
class InjuryTriageTest {

    // --- HIGH_RISK ---

    @Test
    void highRisk_unconscious_isHighSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.HIGH_RISK, Answer.NO, Answer.YES, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void highRisk_noMovement_isHighSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.HIGH_RISK, Answer.YES, Answer.NO, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void highRisk_noMovement_bleedingNo_isHighSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.HIGH_RISK, Answer.YES, Answer.NO, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void highRisk_movementYes_bleedingNo_isMediumSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.HIGH_RISK, Answer.YES, Answer.YES, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void highRisk_movementDontKnow_bleedingNo_isMediumSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.HIGH_RISK, Answer.YES, Answer.DONT_KNOW, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    // --- LOW_RISK ---

    @Test
    void lowRisk_unconscious_isHighSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.LOW_RISK, Answer.NO, Answer.YES, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void lowRisk_bleedingYes_isHighSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.LOW_RISK, Answer.YES, Answer.YES, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void lowRisk_bleedingDontKnow_noMovement_isHighSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.LOW_RISK, Answer.YES, Answer.NO, Answer.DONT_KNOW);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void lowRisk_bleedingDontKnow_movementYes_isMediumSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.LOW_RISK, Answer.YES, Answer.YES, Answer.DONT_KNOW);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void lowRisk_bleedingNo_noMovement_isMediumSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.LOW_RISK, Answer.YES, Answer.NO, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void lowRisk_bleedingNo_movementYes_isLowSeverity() {
        TriageResult result = InjuryTriage.evaluate(BodyPartCategory.LOW_RISK, Answer.YES, Answer.YES, Answer.NO);
        assertEquals(Severity.LOW, result.severity());
    }
}