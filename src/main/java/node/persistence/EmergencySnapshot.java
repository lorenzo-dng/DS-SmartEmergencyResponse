package node.persistence;

import domain.emergency.Emergency;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import java.util.List;
import java.util.Map;

// rappresenta lo stato salvato di un'emergenza, per il recovery dopo un crash: l'emergenza stessa, i veicoli presi in prestito e i veicoli già arrivati sull'emergenza (per categoria)
public record EmergencySnapshot(Emergency emergency, Map<VehicleId, BorrowedVehicleInfo> borrowedVehicles, Map<VehicleCategory, List<VehicleId>> arrivals) {
}