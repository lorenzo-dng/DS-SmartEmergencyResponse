package node.topology;

import domain.common.Position;
import domain.utils.GeoUtils;
import domain.zone.Zone;
import domain.zone.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// costruisce una griglia della città, con confini interni semiaperti [min, max) e confine esterno assoluto chiuso sul lato estremo
public class ZoneGridFactory {

    public static final double MAX_ZONE_SIDE_KM = 12.0; // lato massimo (in km) di una zona
    private static final double GRID_EPSILON = 1e-9;

    // calcola il numero di righe della griglia
    public static int rowsFor(double mapLatMin, double mapLatMax, double mapLonMin) {
        double heightKm = GeoUtils.haversine(new Position(mapLatMin, mapLonMin), new Position(mapLatMax, mapLonMin));
        return Math.max(1, (int) Math.ceil(heightKm / MAX_ZONE_SIDE_KM - GRID_EPSILON));
    }

    // calcola il numero di colonne della griglia
    public static int colsFor(double mapLatMin, double mapLatMax, double mapLonMin, double mapLonMax) {
        double midLat = (mapLatMin + mapLatMax) / 2;
        double widthKm = GeoUtils.haversine(new Position(midLat, mapLonMin), new Position(midLat, mapLonMax));
        return Math.max(1, (int) Math.ceil(widthKm / MAX_ZONE_SIDE_KM - GRID_EPSILON));
    }

    // crea la griglia
    public static Map<ZoneId, Zone> buildGrid(double mapLatMin, double mapLatMax, double mapLonMin, double mapLonMax) {
        return build(rowsFor(mapLatMin, mapLatMax, mapLonMin), colsFor(mapLatMin, mapLatMax, mapLonMin, mapLonMax), mapLatMin, mapLatMax, mapLonMin, mapLonMax);
    }

    // crea la griglia
    private static Map<ZoneId, Zone> build(int rows, int cols, double mapLatMin, double mapLatMax, double mapLonMin, double mapLonMax) {
        double cellHeight = (mapLatMax - mapLatMin) / rows; // altezza di ogni zona
        double cellWidth = (mapLonMax - mapLonMin) / cols; // larghezza di ogni zona

        Map<ZoneId, Zone> grid = new HashMap<>(); // mappa delle zone
        for (int row = 0; row < rows; row++) { // per ogni riga
            for (int col = 0; col < cols; col++) { // considera ogni colonna
                ZoneId id = zoneIdOf(row, col); // genera l'id della zona corrente
                double latMin = mapLatMin + row * cellHeight; // calcola il limite inferiore di latitudine della zona
                double latMax = mapLatMin + (row + 1) * cellHeight; // calcola il limite superiore di latitudine della zona
                double lonMin = mapLonMin + col * cellWidth;  // calcola il limite inferiore di longitudine della zona
                double lonMax = mapLonMin + (col + 1) * cellWidth;  // calcola il limite superiore di longitudine della zona
                boolean isTopRow = (row == rows - 1); // determina se la zona appartiene all'ultima riga in alto
                boolean isRightCol = (col == cols - 1); // determina se la zona appartiene all'ultima colonna a destra
                Set<ZoneId> neighbors = new HashSet<>(); // lista degli id delle zone confinanti
                if (row > 0) neighbors.add(zoneIdOf(row - 1, col)); // verifica se esiste una zona nella riga inferiore e, se esiste, la aggiunge alla lista
                if (row < rows - 1) neighbors.add(zoneIdOf(row + 1, col)); // verifica se esiste una zona nella riga superiore e, se esiste, la aggiunge alla lista
                if (col > 0) neighbors.add(zoneIdOf(row, col - 1)); // verifica se esiste una zona nella colonna a sinistra e, se esiste, la aggiunge alla lista
                if (col < cols - 1) neighbors.add(zoneIdOf(row, col + 1)); // verifica se esiste una zona nella colonna a destra e, se esiste, la aggiunge alla lista
                grid.put(id, new Zone(id, latMin, latMax, lonMin, lonMax, isTopRow, isRightCol, neighbors)); // crea la zona e la aggiunge alla mappa
            }
        }
        return grid; // restituisce la griglia
    }

    // crea l'id della zona
    public static ZoneId zoneIdOf(int row, int col) {
        return new ZoneId("zone-" + row + "-" + col);
    }
}