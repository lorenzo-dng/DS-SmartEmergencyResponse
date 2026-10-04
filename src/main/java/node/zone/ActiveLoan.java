package node.zone;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;

// utilizzato dal contractor: rappresenta un prestito di un veicolo che non ha ancora concluso l'intervento sull'emergenza
public record ActiveLoan(EmergencyId emergencyId, ZoneId requesterZoneId, VehicleCategory category) {
}