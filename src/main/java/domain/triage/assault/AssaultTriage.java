package domain.triage.assault;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class AssaultTriage {

    public static TriageResult evaluate(PeopleInvolved peopleInvolved, Answer injuries) {
        // 1) alta
        if (injuries == Answer.YES) {
            List<VehicleRequirement> vehicles = (peopleInvolved == PeopleInvolved.MORE_THAN_FIVE)
                    ? List.of(new VehicleRequirement(VehicleCategory.POLICE, 2), new VehicleRequirement(VehicleCategory.AMBULANCE, 1))
                    : List.of(new VehicleRequirement(VehicleCategory.POLICE, 1), new VehicleRequirement(VehicleCategory.AMBULANCE, 1)
            );
            return new TriageResult(Severity.HIGH, vehicles);
        }

        // 2) media / bassa
        if (injuries == Answer.NO || injuries == Answer.DONT_KNOW) {
            if (peopleInvolved == PeopleInvolved.MORE_THAN_FIVE) {
                return new TriageResult(Severity.MEDIUM, List.of(new VehicleRequirement(VehicleCategory.POLICE, 2)));
            }
            if (peopleInvolved == PeopleInvolved.THREE_TO_FIVE || peopleInvolved == PeopleInvolved.DONT_KNOW) {
                return new TriageResult(Severity.MEDIUM, List.of(new VehicleRequirement(VehicleCategory.POLICE, 1)));
            }
            return new TriageResult(Severity.LOW, List.of(new VehicleRequirement(VehicleCategory.POLICE, 1)));
        }

        // 3) non classificabile
        throw new IllegalStateException("Unreachable: assault classification incomplete");
    }
}