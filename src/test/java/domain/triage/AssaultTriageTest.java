package domain.triage;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.assault.AssaultTriage;
import domain.triage.assault.PeopleInvolved;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

//unit tests
class AssaultTriageTest {

    private static void assertCategory(TriageResult result, VehicleCategory category, int expectedQuantity) {
        long actual = result.vehiclesNeeded().stream()
                .filter(v -> v.category() == category)
                .mapToInt(VehicleRequirement::quantity)
                .sum();
        assertEquals(expectedQuantity, actual, "Categoria " + category + " con quantità inattesa");
    }

    private static void assertAbsent(TriageResult result, VehicleCategory category) {
        boolean present = result.vehiclesNeeded().stream().anyMatch(v -> v.category() == category);
        assertFalse(present, "Categoria " + category + " non dovrebbe essere presente");
    }

    @Test
    void injuriesYes_moreThanFive_isHighSeverity() {
        TriageResult result = AssaultTriage.evaluate(PeopleInvolved.MORE_THAN_FIVE, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.POLICE, 2);
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
    }

    @Test
    void injuriesYes_fewPeople_isHighSeverity() {
        TriageResult result = AssaultTriage.evaluate(PeopleInvolved.TWO, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.POLICE, 1);
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
    }

    @Test
    void noInjuries_moreThanFive_isMediumSeverity() {
        TriageResult result = AssaultTriage.evaluate(PeopleInvolved.MORE_THAN_FIVE, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
        assertCategory(result, VehicleCategory.POLICE, 2);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    @Test
    void noInjuries_threeToFive_isMediumSeverity() {
        TriageResult result = AssaultTriage.evaluate(PeopleInvolved.THREE_TO_FIVE, Answer.DONT_KNOW);
        assertEquals(Severity.MEDIUM, result.severity());
        assertCategory(result, VehicleCategory.POLICE, 1);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    @Test
    void noInjuries_twoPeople_isLowSeverity() {
        TriageResult result = AssaultTriage.evaluate(PeopleInvolved.TWO, Answer.NO);
        assertEquals(Severity.LOW, result.severity());
        assertCategory(result, VehicleCategory.POLICE, 1);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }
}