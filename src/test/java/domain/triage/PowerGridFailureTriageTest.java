package domain.triage;

import domain.emergency.Severity;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.triage.powergridfailure.PowerGridExtent;
import domain.triage.powergridfailure.PowerGridFailureTriage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

//unit tests
class PowerGridFailureTriageTest {

    @Test
    void exposedWiresYes_isHighSeverity() {
        TriageResult result = PowerGridFailureTriage.evaluate(PowerGridExtent.SINGLE_HOME, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
    }

    @Test
    void exposedWiresDontKnow_isMediumSeverity() {
        TriageResult result = PowerGridFailureTriage.evaluate(PowerGridExtent.SINGLE_HOME, Answer.DONT_KNOW);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void singleHomeExtent_noExposedWires_isLowSeverity() {
        TriageResult result = PowerGridFailureTriage.evaluate(PowerGridExtent.SINGLE_HOME, Answer.NO);
        assertEquals(Severity.LOW, result.severity());
    }
}