package node.vehicle;

import domain.common.Position;
import domain.emergency.Emergency;
import domain.utils.GeoUtils;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleStatus;
import io.vertx.core.Vertx;
import node.zone.ActiveLoan;
import node.zone.InterventionNotifier;
import node.zone.ZoneState;

// gestisce il ciclo di vita di un veicolo dopo l'assegnazione: avanzamento, arrivo, intervento, ritorno alla base
public class VehicleLifecycleManager {

    private static final long POSITION_UPDATE_INTERVAL_MS = 250; // frequenza di aggiornamento della posizione
    private static final long ON_SCENE_DURATION_MS = 2000; // durata dell'intervento
    private static final double MILLIS_PER_HOUR = 3_600_000.0;
    private final ZoneState zoneState;
    private final Vertx vertx;
    private final InterventionNotifier interventionNotifier;

    public VehicleLifecycleManager(ZoneState zoneState, Vertx vertx, InterventionNotifier interventionNotifier) {
        this.zoneState = zoneState;
        this.vertx = vertx;
        this.interventionNotifier = interventionNotifier;
    }

    // avvia il timer periodico che avanza la posizione di tutti i veicoli in movimento
    public void start() {
        vertx.setPeriodic(POSITION_UPDATE_INTERVAL_MS, id -> advanceAllVehicles());
    }

    private void advanceAllVehicles() {
        for (Vehicle vehicle : zoneState.getVehicles()) {
            if (vehicle.getStatus() == VehicleStatus.EN_ROUTE || vehicle.getStatus() == VehicleStatus.RETURNING) {
                advance(vehicle);
            }
        }
    }

    // avanza la posizione del veicolo verso l'emergenza di destinazione
    private void advance(Vehicle vehicle) {
        double remainingKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getDestination());
        double stepKm = GeoUtils.averageSpeedKmh(vehicle.getCategory()) * (POSITION_UPDATE_INTERVAL_MS / MILLIS_PER_HOUR); // distanza percorsa a ogni aggiornamento
        if (remainingKm <= stepKm) { // il prossimo step supererebbe la destinazione: arrivato
            vehicle.setPosition(vehicle.getDestination());
            onArrival(vehicle);
        } else {
            double fraction = stepKm / remainingKm; // converta i km nella copertura del segmento che separa il veicolo alla destinazione ("quanto segmento deve coprire con x stepKm?")
            double newLat = vehicle.getPosition().latitude() + fraction * (vehicle.getDestination().latitude() - vehicle.getPosition().latitude());
            double newLon = vehicle.getPosition().longitude() + fraction * (vehicle.getDestination().longitude() - vehicle.getPosition().longitude());
            vehicle.setPosition(new Position(newLat, newLon));
        }
    }

    private void onArrival(Vehicle vehicle) {
        if (vehicle.getStatus() == VehicleStatus.EN_ROUTE) {
            vehicle.setStatus(VehicleStatus.ON_SCENE);
            vertx.setTimer(ON_SCENE_DURATION_MS, id -> onInterventionComplete(vehicle));
        } else if (vehicle.getStatus() == VehicleStatus.RETURNING) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
            vehicle.setDestination(null);
            zoneState.assignAvailableVehicles(vehicle.getCategory());
            zoneState.reassignEnRouteVehicles(vehicle.getCategory());
        }
    }

    // gestisce la fine dell'intervento sull'emergenza: comportamento diverso per veicoli locali e presi in prestito
    private void onInterventionComplete(Vehicle vehicle) {
        boolean isBorrowed = zoneState.hasActiveLoan(vehicle.getId());
        if (isBorrowed) { // se il veicolo è stato prestato
            ActiveLoan loan = zoneState.getActiveLoan(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No active loan found for borrowed vehicle completing intervention: " + vehicle.getId().value()));
            prepareForReturn(vehicle); // il veicolo torna alla base
            zoneState.savePendingLoan(vehicle.getId()); // salva il prestito nel db
            interventionNotifier.notifyCompletion(loan.emergencyId(), loan.requesterZoneId(), vehicle.getId(), loan.category());
            return;
        }
        Emergency emergency = zoneState.findAssignedEmergency(vehicle.getId()).orElseThrow(() -> new IllegalStateException("No emergency found for local vehicle completing intervention: " + vehicle.getId().value()));
        emergency.markVehicleCompleted(vehicle.getId());
        prepareForReturn(vehicle);
    }

    // prepara il ritorno del veicolo alla base: ricarica di emergenza se necessario, poi avvia il viaggio
    private void prepareForReturn(Vehicle vehicle) {
        double distanceToBaseKm = GeoUtils.haversine(vehicle.getPosition(), vehicle.getBasePosition());
        if (vehicle.getBatteryLevel() < GeoUtils.estimatedBatteryConsumptionPercent(distanceToBaseKm, vehicle.getCategory())) {
            vehicle.setBatteryLevel(100.0); // ricarica di emergenza: solo se la carica residua non basterebbe per il ritorno, sempre da fermo
        }
        vehicle.setDestination(vehicle.getBasePosition());
        vehicle.setStatus(VehicleStatus.RETURNING);
    }
}