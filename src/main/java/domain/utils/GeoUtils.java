package domain.utils;

import domain.common.Position;
import domain.vehicle.VehicleCategory;

public class GeoUtils {

    private static final int EARTH_RADIUS_KM = 6371;
    private static final double GROUND_VEHICLE_SPEED_KMH = 50.0;
    private static final double HELICOPTER_SPEED_KMH = 180.0;
    private static final double GROUND_BATTERY_CONSUMPTION_PERCENT_PER_KM = 0.25;
    private static final double HELICOPTER_BATTERY_CONSUMPTION_PERCENT_PER_KM = 0.6;
    private static final int MINUTES_PER_HOUR = 60;

    // calcola la distanza in km tra due coordinate geografiche
    public static double haversine(Position p1, Position p2) {
        return haversine(p1.latitude(), p1.longitude(), p2.latitude(), p2.longitude());
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon/2) * Math.sin(dLon/2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // tempo di arrivo stimato in minuti
    public static double estimatedArrivalMinutes(double distanceKm, VehicleCategory category) {
        return (distanceKm / averageSpeedKmh(category)) * MINUTES_PER_HOUR;
    }

    // velocita media dei veicoli
    public static double averageSpeedKmh(VehicleCategory category) {
        return category == VehicleCategory.HELICOPTER ? HELICOPTER_SPEED_KMH : GROUND_VEHICLE_SPEED_KMH;
    }

    // consumo medio di batteria dei veicoli per km
    public static double estimatedBatteryConsumptionPercent(double distanceKm, VehicleCategory category) {
        double ratePerKm = (category == VehicleCategory.HELICOPTER) ? HELICOPTER_BATTERY_CONSUMPTION_PERCENT_PER_KM : GROUND_BATTERY_CONSUMPTION_PERCENT_PER_KM;
        return distanceKm * ratePerKm;
    }
}