package domain.triage;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.triage.fire.FireSize;
import domain.triage.fire.FireType;
import domain.triage.fire.FireTriage;
import domain.vehicle.VehicleCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

//unit tests
class FireTriageTest {

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

    // --- BUILDING / OTHER / DONT_KNOW ---

    // punto 1: persone in pericolo = Sì → sempre alta, vigili scalano con dimensione, sempre ambulanza+polizia
    @Test
    void building_dangerYes_containedSize() {
        TriageResult result = FireTriage.evaluate(FireType.BUILDING, FireSize.CONTAINED, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 1);
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
        assertCategory(result, VehicleCategory.POLICE, 1);
    }

    @Test
    void building_dangerYes_mediumSize() {
        TriageResult result = FireTriage.evaluate(FireType.BUILDING, FireSize.MEDIUM, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 2);
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
    }

    @Test
    void building_dangerYes_largeSize() {
        TriageResult result = FireTriage.evaluate(FireType.BUILDING, FireSize.LARGE, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 3);
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
    }

    @Test
    void building_dangerYes_unknownSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.DONT_KNOW, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 1);
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
    }

    // punto 2: No + Grandi → alta, niente ambulanza
    @Test
    void building_dangerNo_largeSize() {
        TriageResult result = FireTriage.evaluate(FireType.BUILDING, FireSize.LARGE, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 3);
        assertCategory(result, VehicleCategory.POLICE, 1);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    // punto 3: No + Medie → media
    @Test
    void building_dangerNo_mediumSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.MEDIUM, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 2);
        assertCategory(result, VehicleCategory.POLICE, 1);
    }

    // punto 4: No + Non lo so (dimensione) → media (ramo che avevamo dimenticato una volta)
    @Test
    void building_dangerNo_unknownSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.DONT_KNOW, Answer.NO);
        assertEquals(Severity.MEDIUM, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 1);
        assertCategory(result, VehicleCategory.POLICE, 1);
    }

    // punto 5: No + Contenute → bassa, niente polizia
    @Test
    void building_dangerNo_containedSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.CONTAINED, Answer.NO);
        assertEquals(Severity.LOW, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 1);
        assertAbsent(result, VehicleCategory.POLICE);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    // punto 6: Non lo so (persone) + Grandi → alta, niente ambulanza (la discrepanza reale trovata prima)
    @Test
    void building_dangerDontKnow_largeSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.LARGE, Answer.DONT_KNOW);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 3);
        assertCategory(result, VehicleCategory.POLICE, 1);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    // punto 7: Non lo so + Medie → media
    @Test
    void building_dangerDontKnow_mediumSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.MEDIUM, Answer.DONT_KNOW);
        assertEquals(Severity.MEDIUM, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 2);
        assertCategory(result, VehicleCategory.POLICE, 1);
    }

    // punto 8: Non lo so + Contenute → media (stesso output di Non lo so+Non lo so, basta uno dei due)
    @Test
    void building_dangerDontKnow_containedSize() {
        TriageResult result = FireTriage.evaluate(FireType.OTHER, FireSize.CONTAINED, Answer.DONT_KNOW);
        assertEquals(Severity.MEDIUM, result.severity());
        assertCategory(result, VehicleCategory.FIRE_SERVICE, 1);
        assertCategory(result, VehicleCategory.POLICE, 1);
    }

    // --- WILDFIRE (solo i punti dove l'elicottero entra in gioco, il resto è già coperto dalla logica condivisa sopra) ---

    @Test
    void wildfire_dangerYes_largeSize_hasHelicopterAndAmbulance() {
        TriageResult result = FireTriage.evaluate(FireType.WILDFIRE, FireSize.LARGE, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.AMBULANCE, 1);
        assertCategory(result, VehicleCategory.HELICOPTER, 1);
    }

    @Test
    void wildfire_dangerNo_largeSize_hasHelicopterNoAmbulance() {
        TriageResult result = FireTriage.evaluate(FireType.WILDFIRE, FireSize.LARGE, Answer.NO);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.HELICOPTER, 1);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    @Test
    void wildfire_dangerDontKnow_largeSize_hasHelicopterNoAmbulance() {
        TriageResult result = FireTriage.evaluate(FireType.WILDFIRE, FireSize.LARGE, Answer.DONT_KNOW);
        assertEquals(Severity.HIGH, result.severity());
        assertCategory(result, VehicleCategory.HELICOPTER, 1);
        assertAbsent(result, VehicleCategory.AMBULANCE);
    }

    @Test
    void wildfire_nonLargeSize_neverHasHelicopter() {
        TriageResult result = FireTriage.evaluate(FireType.WILDFIRE, FireSize.MEDIUM, Answer.YES);
        assertEquals(Severity.HIGH, result.severity());
        assertAbsent(result, VehicleCategory.HELICOPTER);
    }
}