package tools;

import domain.vehicle.VehicleCategory;
import io.vertx.core.json.JsonObject;
import node.topology.ZoneGridFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// genera i file per avviare l'intero sistema con docker compose
public class DeploymentGenerator {

    private static final Path CITIES_FILE = Path.of("cities.json"); // elenco delle città
    private static final int NODE_PORT = 8080; // porta interna di ogni nodo (serve per la comunicazione tra nodi)
    private static final int FIRST_HOST_PORT = 9000; // porta esterna del primo nodo (serve per usare l'app da un host)
    private static final String IMAGE_NAME = "ser-node"; // nome dell'immagine docker associato a tutti i container docker dei nodi
    private static final double BASE_MARGIN = 0.1; // le basi dei veicoli distano tra loro di almeno il 10% della dimensione della zona
    private record MapBounds(double latMin, double latMax, double lonMin, double lonMax) {} // limiti della mappa
    private record City(MapBounds bounds, int rows, int cols, Map<String, Map<VehicleCategory, Integer>> fleet) {} // rappresenta una citta

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { // se non è stata indicata nessuna citta
            throw new IllegalArgumentException("Usage: DeploymentGenerator <city> (cities are listed in " + CITIES_FILE + ")");
        }
        City city = readCity(args[0]);
        Files.writeString(Path.of("vehicles.json"), vehiclesJson(city)); // genera le configurazioni dei veicoli
        Files.writeString(Path.of("docker-compose.yml"), composeYaml(city)); // genera il file docker-compose
        Files.writeString(Path.of("grid.json"), gridJson(city)); // genera i parametri della griglia
        System.out.println("Generated docker-compose.yml, vehicles.json and grid.json for " + args[0] + ": " + city.rows + " rows x " + city.cols + " columns = " + (city.rows * city.cols) + " zones");
    }

    // legge dal file delle città le coordinate della mappa della città indicata
    private static City readCity(String cityName) throws IOException {
        JsonObject cities = new JsonObject(Files.readString(CITIES_FILE));
        JsonObject city = cities.getJsonObject(cityName);
        if (city == null) {
            throw new IllegalArgumentException("Unknown city: " + cityName);
        }
        String source = CITIES_FILE + " (" + cityName + ")";
        MapBounds bounds = readBounds(requireObject(city, "bounds", source), source);
        JsonObject grid = requireObject(city, "grid", source);
        Integer rows = grid.getInteger("rows");
        Integer cols = grid.getInteger("cols");
        if (rows == null || cols == null || rows < 1 || cols < 1) {
            throw new IllegalArgumentException("Invalid grid in " + source + ": rows and cols must be positive numbers");
        }
        return new City(bounds, rows, cols, readFleet(requireObject(city, "fleet", source), zoneIds(rows, cols), source));
    }

    // legge i limiti della mappa
    private static MapBounds readBounds(JsonObject json, String source) {
        MapBounds bounds = new MapBounds(json.getDouble("latMin"), json.getDouble("latMax"), json.getDouble("lonMin"), json.getDouble("lonMax"));
        if (bounds.latMin() >= bounds.latMax() || bounds.lonMin() >= bounds.lonMax()) {
            throw new IllegalArgumentException("Invalid bounds in " + source + ": min must be lower than max");
        }
        return bounds;
    }

    // restituisce la sezione indicata di una citta, nel file delle citta
    private static JsonObject requireObject(JsonObject json, String key, String source) {
        JsonObject section = json.getJsonObject(key);
        if (section == null) {
            throw new IllegalArgumentException("Missing section \"" + key + "\" in " + source);
        }
        return section;
    }


    // legge i veicoli di ogni zona
    private static Map<String, Map<VehicleCategory, Integer>> readFleet(JsonObject fleetJson, List<String> gridZones, String source) {
        if (!new HashSet<>(fleetJson.fieldNames()).equals(new HashSet<>(gridZones))) {
            throw new IllegalArgumentException("The fleet in " + source + " must define exactly the zones of its grid " + gridZones + " (found: " + fleetJson.fieldNames() + ")");
        }
        Map<String, Map<VehicleCategory, Integer>> fleet = new HashMap<>();
        for (String zoneId : gridZones) {
            fleet.put(zoneId, readZoneFleet(fleetJson.getJsonObject(zoneId), source));
        }
        return fleet;
    }

    // legge i veicoli di una zona
    private static Map<VehicleCategory, Integer> readZoneFleet(JsonObject zone, String source) {
        Map<VehicleCategory, Integer> counts = new EnumMap<>(VehicleCategory.class);
        for (String name : zone.fieldNames()) { // per ogni categoria
            VehicleCategory category;
            try {
                category = VehicleCategory.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown vehicle category in " + source + ": " + name + " (valid: " + Arrays.toString(VehicleCategory.values()) + ")");
            }
            Integer count = zone.getInteger(name);
            if (count == null || count < 0) {
                throw new IllegalArgumentException("Invalid count for " + name + " in " + source);
            }
            counts.put(category, count);
        }
        return counts;
    }

    // restituisce l'id delle zone della griglia
    private static List<String> zoneIds(int rows, int cols) {
        List<String> zoneIds = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                zoneIds.add(ZoneGridFactory.zoneIdOf(row, col).value());
            }
        }
        return zoneIds;
    }

    // genera la configurazione dei veicoli
    private static String vehiclesJson(City city) {
        List<String> zones = new ArrayList<>();
        for (int row = 0; row < city.rows; row++) { // per ogni riga
            for (int col = 0; col < city.cols; col++) { // per ogni colonna
                zones.add(zoneVehicles(row, col, city)); // aggiunge alla zona tutta la lista di coppie di coordinate (lat, lon) dei veicoli di ogni categoria
            }
        }
        return "{\n" + String.join(",\n", zones) + "\n}\n";
    }

    // genera la sezione dei veicoli di una zona:
    private static String zoneVehicles(int row, int col, City city) {
        MapBounds map = city.bounds();
        double cellHeight = (map.latMax() - map.latMin()) / city.rows; // altezza della cella
        double cellWidth = (map.lonMax() - map.lonMin()) / city.cols; // larghezza della cella
        double zoneLatMin = map.latMin() + row * cellHeight; // latitudine minima
        double zoneLonMin = map.lonMin() + col * cellWidth; // longitudine minima
        String zoneId = ZoneGridFactory.zoneIdOf(row, col).value();
        Random random = new Random(31L * row + col); // viene utilizzate come valore diverso (randomico) per calcolare la posizione base di ogni categoria di veicoli (ma deterministico: è sempre quello ogni volta che si avvia il programma)
        List<String> categories = new ArrayList<>();
        for (VehicleCategory category : VehicleCategory.values()) { // per ogni categoria di veicolo
            int count = city.fleet().get(zoneId).getOrDefault(category, 0); // recupera il numero di veicoli della categoria
            if (count == 0) {
                continue;
            }
            double lat = zoneLatMin + cellHeight * (BASE_MARGIN + (1 - 2 * BASE_MARGIN) * random.nextDouble()); // latitudine della base dei veicoli di quella categoria
            double lon = zoneLonMin + cellWidth * (BASE_MARGIN + (1 - 2 * BASE_MARGIN) * random.nextDouble()); // longitudine della base dei veicoli di quella categoria
            String base = String.format(Locale.ROOT, "{\"lat\": %.6f, \"lon\": %.6f}", lat, lon); // crea un oggetto JSON contenente le coordinate
            categories.add("    \"" + category.name() + "\": [" + String.join(", ", Collections.nCopies(count, base)) + "]"); // aggiunge alla lista tutte le coppie di coordinate (lat, lon) dei veicoli di ogni categoria
        }
        return "  \"" + ZoneGridFactory.zoneIdOf(row, col).value() + "\": {\n" + String.join(",\n", categories) + "\n  }";
    }

    // genera il file docker-compose per ogni zona
    private static String composeYaml(City city) {
        StringBuilder services = new StringBuilder("name: smart-emergency-response\nservices:\n");
        StringBuilder volumes = new StringBuilder("volumes:\n"); // crea una stringa di volumi, per fare in modo che il db di ogni nodo sopravviva al riavvio del container
        for (int row = 0; row < city.rows; row++) { // per ogni riga della griglia
            for (int col = 0; col < city.cols; col++) { // per ogni colonna della griglia
                String zoneId = ZoneGridFactory.zoneIdOf(row, col).value(); // ottiene l'id della zona corrente
                services.append(serviceYaml(zoneId, row, col, FIRST_HOST_PORT + row * city.cols + col, city));
                volumes.append("  ").append(zoneId).append("-data:\n"); // popola volumes con il nome della zona
            }
        }
        return services.append(volumes).toString();
    }

    // genera il servizio di una zona
    private static String serviceYaml(String zoneId, int row, int col, int hostPort, City city) {
        MapBounds map = city.bounds;
        return "  " + zoneId + ":\n" +
                "    build: .\n" +
                "    image: " + IMAGE_NAME + "\n" +
                "    environment:\n" +
                "      ZONE_ROW: \"" + row + "\"\n" +
                "      ZONE_COL: \"" + col + "\"\n" +
                "      GRID_ROWS: \"" + city.rows + "\"\n" +
                "      GRID_COLS: \"" + city.cols + "\"\n" +
                String.format(Locale.ROOT, "      MAP_LAT_MIN: \"%.6f\"\n", map.latMin()) +
                String.format(Locale.ROOT, "      MAP_LAT_MAX: \"%.6f\"\n", map.latMax()) +
                String.format(Locale.ROOT, "      MAP_LON_MIN: \"%.6f\"\n", map.lonMin()) +
                String.format(Locale.ROOT, "      MAP_LON_MAX: \"%.6f\"\n", map.lonMax()) +
                "      HTTP_PORT: \"" + NODE_PORT + "\"\n" +
                "      VEHICLES_CONFIG_PATH: /config/vehicles.json\n" +
                "      DB_PATH: /data/node.db\n" +
                "    ports:\n" +
                "      - \"" + hostPort + ":" + NODE_PORT + "\"\n" +
                "    volumes:\n" +
                "      - ./vehicles.json:/config/vehicles.json:ro\n" +
                "      - " + zoneId + "-data:/data\n";
    }

    // genera i parametri della griglia per la gui
    private static String gridJson(City city) {
        MapBounds map = city.bounds;
        List<String> zones = new ArrayList<>();
        for (int row = 0; row < city.rows; row++) { // per ogni riga della griglia
            for (int col = 0; col < city.cols; col++) { // per ogni colonna della griglia
                /*
                crea un oggetto JSON con:
                    - id della zona
                    - coordinate della zona nella griglia (row e col)
                    - porta sull'host a cui la GUI dovrà connettersi per parlare con quel nodo
                 */
                zones.add(String.format(Locale.ROOT, "    {\"id\": \"%s\", \"row\": %d, \"col\": %d, \"port\": %d}", ZoneGridFactory.zoneIdOf(row, col).value(), row, col, FIRST_HOST_PORT + row * city.cols + col));
            }
        }
        /*
        costruisce un oggetto JSON finale della griglia con:
            - i confini geografici della mappa
            - le dimensioni della griglia
            - l'array "zones" contenente tutti gli oggetti creati
        */
        return String.format(Locale.ROOT, "{\n  \"mapLatMin\": %.6f,\n  \"mapLatMax\": %.6f,\n  \"mapLonMin\": %.6f,\n  \"mapLonMax\": %.6f,\n  \"rows\": %d,\n  \"cols\": %d,\n  \"zones\": [\n%s\n  ]\n}\n", map.latMin(), map.latMax(), map.lonMin(), map.lonMax(), city.rows, city.cols, String.join(",\n", zones));
    }
}