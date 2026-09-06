package domain.triage.fire;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import domain.triage.common.Answer;
import domain.triage.common.TriageResult;
import domain.vehicle.VehicleCategory;
import java.util.ArrayList;
import java.util.List;

// classe di valutazione (gravita + veicoli) dell'emergenza
public class FireTriage {

    public static TriageResult evaluate(FireType fireType, FireSize size, Answer dangerToPeopleOrBuildings) {
        boolean wildfire = fireType == FireType.WILDFIRE;
        return evaluate(size, dangerToPeopleOrBuildings, wildfire);
    }

    private static TriageResult evaluate(FireSize size, Answer danger, boolean wildfire) {
        int fireServiceCount = switch (size) {
            case CONTAINED, DONT_KNOW -> 1;
            case MEDIUM -> 2;
            case LARGE -> 3;
        };
        Severity severity = getSeverity(size, danger);

        boolean includeAmbulance = danger == Answer.YES;
        boolean includePolice = !(danger == Answer.NO && size == FireSize.CONTAINED);
        boolean includeHelicopter = wildfire && size == FireSize.LARGE;

        List<VehicleRequirement> vehicles = new ArrayList<>();
        vehicles.add(new VehicleRequirement(VehicleCategory.FIRE_SERVICE, fireServiceCount));
        if (includeAmbulance) vehicles.add(new VehicleRequirement(VehicleCategory.AMBULANCE, 1));
        if (includePolice) vehicles.add(new VehicleRequirement(VehicleCategory.POLICE, 1));
        if (includeHelicopter) vehicles.add(new VehicleRequirement(VehicleCategory.HELICOPTER, 1));

        return new TriageResult(severity, vehicles);
    }

    private static Severity getSeverity(FireSize size, Answer danger) {
        Severity severity;
        if (danger == Answer.YES) {
            severity = Severity.HIGH;
        } else if (size == FireSize.LARGE) {
            severity = Severity.HIGH;
        } else if (size == FireSize.MEDIUM) {
            severity = Severity.MEDIUM;
        } else if (size == FireSize.CONTAINED) {
            severity = (danger == Answer.NO) ? Severity.LOW : Severity.MEDIUM;
        } else {
            severity = Severity.MEDIUM;
        }
        return severity;
    }
}