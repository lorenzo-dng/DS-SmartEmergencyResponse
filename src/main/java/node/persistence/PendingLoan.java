package node.persistence;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;

// utilizzato dal contractor: rappresenta un prestito di un veicolo che ha concluso l'intervento sull'emergenza
public record PendingLoan(VehicleId vehicleId, EmergencyId emergencyId, ZoneId requesterZoneId, VehicleCategory category) {

}