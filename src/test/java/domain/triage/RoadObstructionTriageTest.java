package domain.triage;

import domain.emergency.Severity;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.triage.roadobstruction.ObstacleType;
import domain.triage.roadobstruction.RoadObstructionTriage;
import domain.vehicle.VehicleCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

//unit tests
class RoadObstructionTriageTest {

    @Test
    void injuriesYes_animal_isHighSeverity_usesFireService() {
        TriageResult result = RoadObstructionTriage.evaluate(ObstacleType.ANIMAL, Answer.NO, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertTrue(result.vehiclesNeeded().stream().anyMatch(v -> v.category() == VehicleCategory.FIRE_SERVICE));
    }

    @Test
    void injuriesYes_debris_isHighSeverity_usesTechnicalRescue() {
        TriageResult result = RoadObstructionTriage.evaluate(ObstacleType.DEBRIS, Answer.NO, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertTrue(result.vehiclesNeeded().stream().anyMatch(v -> v.category() == VehicleCategory.TECHNICAL_RESCUE));
    }

    @Test
    void roadBlockedYes_isMediumSeverity() {
        TriageResult result = RoadObstructionTriage.evaluate(ObstacleType.VEHICLE, Answer.YES, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void roadNotBlocked_noInjuries_isLowSeverity() {
        TriageResult result = RoadObstructionTriage.evaluate(ObstacleType.OTHER, Answer.NO, Answer.NO);
        assertEquals(Severity.LOW, result.severity());
    }
}