package node.zone;

import domain.zone.Zone;
import domain.zone.ZoneId;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleId;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleStatus;
import domain.emergency.Emergency;
import domain.emergency.EmergencyId;
import domain.emergency.EmergencyStatus;
import domain.common.VectorClock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// stato della zona
public class ZoneState {

    private final Zone zone;
    private final Map<VehicleId, Vehicle> vehicles;
    private final Map<EmergencyId, Emergency> emergencies;
    private VectorClock localClock;

    public ZoneState(Zone zone) {
        this.zone = zone;
        this.vehicles = new HashMap<>();
        this.emergencies = new HashMap<>();
        this.localClock = new VectorClock();
    }

    public ZoneId getZoneId() {
        return zone.getId();
    }

    public Zone getZone() {
        return zone;
    }

    // veicoli

    public void addVehicle(Vehicle vehicle) {
        vehicles.put(vehicle.getId(), vehicle);
    }

    public Vehicle getVehicle(VehicleId id) {
        Vehicle vehicle = vehicles.get(id);
        if (vehicle == null) {
            throw new IllegalArgumentException("Vehicle not found in this zone: " + id);
        }
        return vehicle;
    }

    // vista di sola lettura, per evitare che i veicoli possano essere rimossi o aggiunti dalla zona dall'esterno
    public Collection<Vehicle> getVehicles() {
        return Collections.unmodifiableCollection(vehicles.values());
    }

    // recupera i veicoli liberi di una specifica categoria
    public List<Vehicle> getAvailableVehicles(VehicleCategory category) {
        List<Vehicle> available = new ArrayList<>();
        for (Vehicle vehicle : vehicles.values()) {
            if (vehicle.getCategory() == category && vehicle.getStatus() == VehicleStatus.AVAILABLE) {
                available.add(vehicle);
            }
        }
        return available;
    }

    // emergenze

    public void addEmergency(Emergency emergency) {
        emergencies.put(emergency.getId(), emergency);
    }

    public Emergency getEmergency(EmergencyId id) {
        Emergency emergency = emergencies.get(id);
        if (emergency == null) {
            throw new IllegalArgumentException("Emergency not found in this zone: " + id);
        }
        return emergency;
    }

    // recupera le emergenze ancora aperte
    public Collection<Emergency> getOpenEmergencies() {
        List<Emergency> open = new ArrayList<>();
        for (Emergency emergency : emergencies.values()) {
            if (emergency.getStatus() != EmergencyStatus.CLOSED) {
                open.add(emergency);
            }
        }
        return Collections.unmodifiableList(open);
    }

    // vector clock

    // restituisce il proprio vector clock
    public VectorClock getLocalClock() {
        return localClock;
    }

    // incrementa di 1 il proprio vector clock
    public void recordLocalEvent() {
        this.localClock = localClock.increment(getZoneId());
    }

    // unisce il proprio vector clock con un altro prendendo il massimo di ciascun vector clock
    public void mergeClock(VectorClock other) {
        this.localClock = localClock.merge(other);
    }

    // ripristina il vector clock dopo il crash del nodo
    public void restoreClock(VectorClock recovered) {
        this.localClock = recovered;
    }
}