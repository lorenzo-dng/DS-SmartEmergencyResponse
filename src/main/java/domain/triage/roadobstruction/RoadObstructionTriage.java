package domain.triage.roadobstruction;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class RoadObstructionTriage {

    public static TriageResult evaluate(ObstacleType obstacleType, Answer roadBlocked, Answer injuries) {
        VehicleCategory rescueCategory = (obstacleType == ObstacleType.ANIMAL)
                ? VehicleCategory.FIRE_SERVICE
                : VehicleCategory.TECHNICAL_RESCUE;

        // 1) alta
        if (injuries == Answer.YES) {
            return new TriageResult(Severity.HIGH, List.of(
                    new VehicleRequirement(rescueCategory, 1),
                    new VehicleRequirement(VehicleCategory.POLICE, 1),
                    new VehicleRequirement(VehicleCategory.AMBULANCE, 1)
            ));
        }

        // 2) media
        if (injuries == Answer.DONT_KNOW || roadBlocked == Answer.YES || roadBlocked == Answer.DONT_KNOW) {
            return new TriageResult(Severity.MEDIUM, List.of(
                    new VehicleRequirement(rescueCategory, 1),
                    new VehicleRequirement(VehicleCategory.POLICE, 1)
            ));
        }

        // 3) bassa
        if (roadBlocked == Answer.NO) {
            return new TriageResult(Severity.LOW, List.of(new VehicleRequirement(rescueCategory, 1)));
        }

        // 4) non classificabile
        throw new IllegalStateException("Unreachable: road obstruction classification incomplete");
    }
}