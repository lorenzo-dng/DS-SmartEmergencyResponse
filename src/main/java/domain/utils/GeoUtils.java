package domain.utils;

import domain.common.Position;

public class GeoUtils {

    private static final int EARTH_RADIUS_KM = 6371;

    //calculates the distance in kilometers between two geographic coordinates
    public static double haversine(Position p1, Position p2) {
        return haversine(p1.latitude(), p1.longitude(), p2.latitude(), p2.longitude());
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon/2) * Math.sin(dLon/2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}