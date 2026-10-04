package node.zone;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;

// utilizzato dall'initiator: chiave di un veicolo preso in prestito da un contractor
public record BorrowedVehicleKey (VehicleId vehicleId, EmergencyId emergencyId) {

}

