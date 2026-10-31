package node.persistence;

import domain.vehicle.VehicleCategory;
import domain.zone.ZoneId;

// utilizzato dall'initiator: rappresenta un veicolo preso in prestito da un contractor
public record BorrowedVehicleInfo(VehicleCategory category, ZoneId contractorZoneId, boolean confirmationAcked, Long deadlineMillis, boolean deadlineAcked) {

    // veicolo appena scelto in fase 3, ma la conferma non è ancora stata ricevuta e la scadenza non è ancora nota
    public static BorrowedVehicleInfo requested(VehicleCategory category, ZoneId contractorZoneId) {
        return new BorrowedVehicleInfo(category, contractorZoneId, false, null, false);
    }

    public BorrowedVehicleInfo withConfirmationAcked() {
        return new BorrowedVehicleInfo(category, contractorZoneId, true, deadlineMillis, deadlineAcked);
    }

    // fissa la scadenza: il contractor non l'ha ancora ricevuta
    public BorrowedVehicleInfo withDeadline(long newDeadlineMillis) {
        return new BorrowedVehicleInfo(category, contractorZoneId, confirmationAcked, newDeadlineMillis, false);
    }

    public BorrowedVehicleInfo withDeadlineAcked() {
        return new BorrowedVehicleInfo(category, contractorZoneId, confirmationAcked, deadlineMillis, true);
    }
}