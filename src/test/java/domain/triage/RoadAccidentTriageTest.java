package domain.triage;

import domain.emergency.Severity;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.triage.roadaccident.RoadAccidentTriage;
import domain.triage.roadaccident.VehiclesInvolved;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

//unit tests
class RoadAccidentTriageTest {

    @Test
    void trapped_isHighSeverity_withThreeVehicleTypes() {
        TriageResult result = RoadAccidentTriage.evaluate(VehiclesInvolved.ONE, Answer.NO, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertEquals(3, result.vehiclesNeeded().size());
    }

    @Test
    void injuriesYes_notTrapped_threeOrMoreVehicles_isHighSeverity_twoAmbulances() {
        TriageResult result = RoadAccidentTriage.evaluate(VehiclesInvolved.THREE_OR_MORE, Answer.YES, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
        assertEquals(2, result.vehiclesNeeded().size());
    }

    @Test
    void injuriesYes_notTrapped_fewVehicles_isHighSeverity_baseVehicles() {
        TriageResult result = RoadAccidentTriage.evaluate(VehiclesInvolved.ONE, Answer.YES, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
        assertEquals(2, result.vehiclesNeeded().size());
    }

    @Test
    void noInjuriesNorTrapped_threeOrMoreVehicles_isMediumSeverity() {
        TriageResult result = RoadAccidentTriage.evaluate(VehiclesInvolved.THREE_OR_MORE, Answer.NO, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void noInjuriesNorTrapped_oneOrTwoVehicles_isLowSeverity() {
        TriageResult result = RoadAccidentTriage.evaluate(VehiclesInvolved.TWO, Answer.NO, Answer.NO);
        assertEquals(Severity.LOW, result.severity());
    }
}