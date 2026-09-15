package node.vehicle;

import domain.common.Position;
import domain.vehicle.Vehicle;
import domain.vehicle.VehicleCategory;
import domain.vehicle.VehicleId;
import domain.zone.ZoneId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import node.zone.ZoneState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

//legge il file di configurazione dei veicoli e popola la zona con i veicoli
public class VehiclePopulator {

    private static final double INITIAL_BATTERY_LEVEL = 100.0;

    public static void populate(ZoneState zoneState, Path configPath) {
        JsonObject root;
        try {
            String content = Files.readString(configPath); // legge il contenuto del file come testo grezzo
            root = new JsonObject(content); // interpreta il testo come oggetto JSON
        } catch (IOException e) { // il file non esiste
            throw new IllegalStateException("Cannot read vehicles config file: " + configPath, e);
        }
        ZoneId zoneId = zoneState.getZoneId();
        JsonObject zoneConfig = root.getJsonObject(zoneId.value()); // estrae dal json solo la sezione relativa alla propria zona
        if (zoneConfig == null) { // nel file non esiste la sezione relativa alla propria zona
            throw new IllegalStateException("No vehicle configuration found for zone: " + zoneId.value());
        }
        for (VehicleCategory category : VehicleCategory.values()) { // per ogni categoria di veicolo
            JsonArray positions = zoneConfig.getJsonArray(category.name()); // prende tutte le posizioni dei veicoli presenti in quella sezione del json
            if (positions == null) { // se la zona non ha veicoli di questa categoria, si passa alla categoria successiva
                continue;
            }

            for (int i = 0; i < positions.size(); i++) { // per ogni posizione elencata
                JsonObject posJson = positions.getJsonObject(i); // prende il contenuto in json
                Position position = new Position(posJson.getDouble("lat"), posJson.getDouble("lon")); // converte il json nel valore Position di dominio
                VehicleId vehicleId = new VehicleId(zoneId.value() + "-" + category.name() + "-" + i); // costruisce un id univoco combinando zona + categoria + indice, es. "zone-0-0-AMBULANCE-0"
                Vehicle vehicle = new Vehicle(vehicleId, category, zoneId, position, INITIAL_BATTERY_LEVEL); // crea il veicolo vero e proprio con batteria piena
                zoneState.addVehicle(vehicle); // aggiunge il veicolo alla zona
            }
        }
    }
}