package node.config;

import domain.zone.ZoneId;

// record che preleva dal docker-compose le informazioni riguardanti la posizione della griglia sulla mappa e la posizione in griglia della zona
public record NodeConfig(int zoneRow, int zoneCol, int gridRows, int gridCols, double mapLatMin, double mapLatMax,
                         double mapLonMin, double mapLonMax, int httpPort, String vehiclesConfigPath,
                         int neighborTimeoutMs, int reservationTimeoutMs) {

    private static final int DEFAULT_NEIGHBOR_TIMEOUT_MS = 2000;
    private static final int DEFAULT_RESERVATION_TIMEOUT_MS = 5000;

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
                parseIntEnv("HTTP_PORT"), // porta su cui il nodo è in ascolto
                parseStringEnv("VEHICLES_CONFIG_PATH"), // percorso di configurazione dei veicoli
                parseIntEnvOrDefault("NEIGHBOR_TIMEOUT_MS", DEFAULT_NEIGHBOR_TIMEOUT_MS), // timeout (ms) per le richieste HTTP verso i nodi vicini
                parseIntEnvOrDefault("RESERVATION_TIMEOUT_MS", DEFAULT_RESERVATION_TIMEOUT_MS) // timeout (ms) per l'attesa della prenotazione del veicolo, dopo il quale il veicolo torna libero
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

    private static String parseStringEnv(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static int parseIntEnvOrDefault(String name, int defaultValue) {
        String value = System.getenv(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    // calcola l'indirizzo di rete di un'altra zona (per comunicare con essa), dato che viene utilizzata un'unica porta per tutte le zone (id zona + port)
    public String addressFor(ZoneId zoneId) {
        return zoneId.value() + ":" + httpPort;
    }
}