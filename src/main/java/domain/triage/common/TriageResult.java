package domain.triage.common;

import domain.emergency.Severity;
import domain.emergency.VehicleRequirement;
import java.util.List;

//dato che contiene le informazioni delle emergenze richieste (gravità + veicoli)
public record TriageResult(Severity severity, List<VehicleRequirement> vehiclesNeeded) {
    public TriageResult {
        if (severity == null) {
            throw new IllegalArgumentException("Severity cannot be null");
        }
        if (vehiclesNeeded == null || vehiclesNeeded.isEmpty()) {
            throw new IllegalArgumentException("VehiclesNeeded cannot be null or empty");
        }
    }
}