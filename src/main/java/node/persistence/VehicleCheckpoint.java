package node.persistence;

import domain.common.Position;
import domain.emergency.EmergencyId;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleStatus;
import domain.zone.ZoneId;

// rappresenta l'ultimo stato noto di un veicolo non AVAILABLE, per il recovery dopo un crash
public record VehicleCheckpoint(VehicleId vehicleId, VehicleStatus status, Position currentPosition,
                                Position destination, double batteryLevel, long checkpointAtMillis,
                                EmergencyId emergencyId, ZoneId requesterZoneId, Long onSceneStartedAtMillis,
                                Long onSceneDeadlineMillis) {
}