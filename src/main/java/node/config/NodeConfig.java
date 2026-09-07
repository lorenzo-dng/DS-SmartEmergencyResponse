package node.config;

import domain.zone.ZoneId;

// record che preleva dal docker-compose le informazioni riguardanti la posizione della griglia sulla mappa e la posizione in griglia della zona
public record NodeConfig(int zoneRow, int zoneCol, int gridRows, int gridCols, double mapLatMin, double mapLatMax, double mapLonMin, double mapLonMax, int httpPort) {
    public static NodeConfig fromEnvironment() {
        return new NodeConfig(
                parseIntEnv("ZONE_ROW"), // riga della zona nella griglia
                parseIntEnv("ZONE_COL"), // colonna della zona nella griglia
                parseIntEnv("GRID_ROWS"), // righe della griglia
                parseIntEnv("GRID_COLS"), // colonne della griglia
                parseDoubleEnv("MAP_LAT_MIN"), // latitudine minima dell'intera mappa (bordo sud)
                parseDoubleEnv("MAP_LAT_MAX"), // latitudine massima dell'intera mappa (bordo nord)
                parseDoubleEnv("MAP_LON_MIN"), // longitudine minima dell'intera mappa (bordo ovest)
                parseDoubleEnv("MAP_LON_MAX"), // longitudine massima dell'intera mappa (bordo est)
                parseIntEnv("HTTP_PORT") // porta su cui il nodo è in ascolto
        );
    }

    private static int parseIntEnv(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return Integer.parseInt(value);
    }

    private static double parseDoubleEnv(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return Double.parseDouble(value);
    }

    // calcola l'indirizzo di rete di un'altra zona (per comunicare con essa), dato che viene utilizzata un'unica porta per tutte le zone (addressFor + port)
    public String addressFor(ZoneId zoneId) {
        return zoneId.value() + ":" + httpPort;
    }
}