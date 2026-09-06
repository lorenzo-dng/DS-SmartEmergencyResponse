package domain.vehicle;

import domain.common.Position;
import domain.zone.ZoneId;

public class Vehicle {
    private final VehicleId id;
    private final VehicleCategory category;
    private final ZoneId homeZone;
    private ZoneId currentAuthority;
    private Position position;
    private VehicleStatus status;
    private double batteryLevel;

    public Vehicle(VehicleId id, VehicleCategory category, ZoneId homeZone, Position position, double batteryLevel) {
        this.id = id;
        this.category = category;
        this.homeZone = homeZone;
        this.currentAuthority = homeZone;
        this.position = position;
        this.status = VehicleStatus.AVAILABLE;
        setBatteryLevel(batteryLevel);
    }

    public VehicleId getId() {
        return id;
    }

    public VehicleCategory getCategory() {
        return category;
    }

    public ZoneId getHomeZone() {
        return homeZone;
    }

    public ZoneId getCurrentAuthority() {
        return currentAuthority;
    }

    public void setCurrentAuthority(ZoneId currentAuthority) {
        this.currentAuthority = currentAuthority;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public double getBatteryLevel() {
        return batteryLevel;
    }

    public void setBatteryLevel(double batteryLevel) {
        if (batteryLevel < 0.0 || batteryLevel > 100.0) {
            throw new IllegalArgumentException("Battery level must be in [0, 100]: " + batteryLevel);
        }
        this.batteryLevel = batteryLevel;
    }
}