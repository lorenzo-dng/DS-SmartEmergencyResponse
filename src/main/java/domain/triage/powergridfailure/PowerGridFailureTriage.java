package domain.triage.powergridfailure;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class PowerGridFailureTriage {

    public static TriageResult evaluate(PowerGridExtent extent, Answer exposedWires) {
        // 1) alta
        if (exposedWires == Answer.YES || extent == PowerGridExtent.NEIGHBORHOOD) {
            return new TriageResult(Severity.HIGH, List.of(
                    new VehicleRequirement(VehicleCategory.TECHNICAL_RESCUE, 1),
                    new VehicleRequirement(VehicleCategory.POLICE, 1)
            ));
        }

        // 2) media
        if (exposedWires == Answer.DONT_KNOW || extent == PowerGridExtent.BUILDING || extent == PowerGridExtent.DONT_KNOW) {
            return new TriageResult(Severity.MEDIUM, List.of(new VehicleRequirement(VehicleCategory.TECHNICAL_RESCUE, 1)));
        }

        // 3) bassa
        if (extent == PowerGridExtent.SINGLE_HOME) {
            return new TriageResult(Severity.LOW, List.of(new VehicleRequirement(VehicleCategory.TECHNICAL_RESCUE, 1)));
        }

        // 4) non classificabile
        throw new IllegalStateException("Unreachable: power grid failure classification incomplete");
    }
}