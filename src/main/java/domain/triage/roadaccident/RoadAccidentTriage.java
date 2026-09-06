package domain.triage.roadaccident;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class RoadAccidentTriage {

    public static TriageResult evaluate(VehiclesInvolved vehiclesInvolved, Answer injuries, Answer trapped) {
        boolean injuriesYesOrUnknown = injuries == Answer.YES || injuries == Answer.DONT_KNOW;
        boolean trappedYesOrUnknown = trapped == Answer.YES || trapped == Answer.DONT_KNOW;

        // 1) alta
        if (injuriesYesOrUnknown || trappedYesOrUnknown) {
            List<VehicleRequirement> vehicles = getVehicles(vehiclesInvolved, trappedYesOrUnknown);
            return new TriageResult(Severity.HIGH, vehicles);
        }

        // 2) media
        if (vehiclesInvolved == VehiclesInvolved.THREE_OR_MORE || vehiclesInvolved == VehiclesInvolved.DONT_KNOW) {
            return new TriageResult(Severity.MEDIUM, List.of(new VehicleRequirement(VehicleCategory.POLICE, 2)));
        }

        // 3) bassa
        if (vehiclesInvolved == VehiclesInvolved.ONE || vehiclesInvolved == VehiclesInvolved.TWO) {
            return new TriageResult(Severity.LOW, List.of(new VehicleRequirement(VehicleCategory.POLICE, 1)));
        }

        // 4) non classificabile
        throw new IllegalStateException("Unreachable: road accident classification incomplete");
    }

    private static List<VehicleRequirement> getVehicles(VehiclesInvolved vehiclesInvolved, boolean trappedYesOrUnknown) {
        List<VehicleRequirement> vehicles;
        if (trappedYesOrUnknown) {
            vehicles = List.of(
                    new VehicleRequirement(VehicleCategory.AMBULANCE, 1),
                    new VehicleRequirement(VehicleCategory.POLICE, 1),
                    new VehicleRequirement(VehicleCategory.FIRE_SERVICE, 1)
            );
        } else if (vehiclesInvolved == VehiclesInvolved.THREE_OR_MORE) {
            vehicles = List.of(
                    new VehicleRequirement(VehicleCategory.AMBULANCE, 2),
                    new VehicleRequirement(VehicleCategory.POLICE, 1)
            );
        } else {
            vehicles = List.of(
                    new VehicleRequirement(VehicleCategory.AMBULANCE, 1),
                    new VehicleRequirement(VehicleCategory.POLICE, 1)
            );
        }
        return vehicles;
    }
}