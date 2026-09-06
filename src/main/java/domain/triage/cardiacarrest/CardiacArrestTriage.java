package domain.triage.cardiacarrest;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class CardiacArrestTriage {

    private static final TriageResult RESULT = new TriageResult(Severity.HIGH, List.of(new VehicleRequirement(VehicleCategory.AMBULANCE, 1)));

    public static TriageResult evaluate() {
        return RESULT;
    }
}