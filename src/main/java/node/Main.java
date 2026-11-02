package node;

import communication.NeighborHealth;
import communication.contractnet.ContractNetNodeClient;
import communication.handoff.NodeClient;
import domain.zone.Zone;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import node.config.NodeConfig;
import node.persistence.*;
import node.server.NodeServer;
import node.topology.ZoneGridFactory;
import node.vehicle.VehicleLifecycleManager;
import node.vehicle.VehiclePopulator;
import node.zone.*;

import java.nio.file.Path;
import java.util.Map;

// crea una zona
public class Main {

    public static void main(String[] args) {
        NodeConfig config = NodeConfig.fromEnvironment(); // legge i parametri della zona da creare dalle variabili d'ambiente Docker (proprie del container avviato)
        ZoneId myZoneId = ZoneGridFactory.zoneIdOf(config.zoneRow(), config.zoneCol()); // definisce l'id della zona
        Map<ZoneId, Zone> grid = ZoneGridFactory.buildGrid(config.gridRows(), config.gridCols(), config.mapLatMin(), config.mapLatMax(), config.mapLonMin(), config.mapLonMax()); // costruisce la griglia
        Zone myZone = grid.get(myZoneId); // crea la zona (cercandola per id nella mappa restituita dalla creazione della griglia)
        if (myZone == null) {
            throw new IllegalStateException("Zone not found in computed grid: " + myZoneId);
        }
        SqliteDatabase database = new SqliteDatabase(config.dbPath());
        LoanRepository loanRepository = new LoanRepository(database); // inizializza il repository dei prestiti cross-zona
        VehicleRepository vehicleRepository = new VehicleRepository(database);
        EmergencyRepository emergencyRepository = new EmergencyRepository(database);
        ZoneState zoneState = new ZoneState(myZone, database, loanRepository, vehicleRepository, emergencyRepository); //inizializza lo stato della zona
        VehiclePopulator.populate(zoneState, Path.of(config.vehiclesConfigPath())); // popola i veicoli iniziali della zona

        Vertx vertx = Vertx.vertx();

        NeighborHealth neighborHealth = new NeighborHealth(myZone.getNeighbors());
        NodeClient client = new NodeClient(vertx, config::addressFor, neighborHealth, config.neighborTimeoutMs(), config.handoffRetryIntervalMs());
        ContractNetNodeClient contractNetClient = new ContractNetNodeClient(vertx, config::addressFor, neighborHealth, config.neighborTimeoutMs());
        // TODO: usare emergencyDispatcher.dispatch(...) nella logica di gestione emergenze (endpoint che riceve le nuove emergenze)
        EmergencyDispatcher emergencyDispatcher = new EmergencyDispatcher(zoneState, contractNetClient, myZone.getNeighbors());
        VehicleLifecycleManager lifecycleManager = new VehicleLifecycleManager(zoneState, vertx, vehicleRepository, client);

        // ripristina lo stato salvato prima del crash
        zoneState.restoreEmergencies(); // ricarica dal db le emergenze aperte (è fondamentale che sia qui e non nel verticle perche è un'operazione bloccante dato che accede al db, e l'event loop non dovrebbe mai bloccarsi)
        lifecycleManager.restoreVehicles(vehicleRepository.loadAll()); // ricarica dal db i veicoli non AVAILABLE

        vertx.deployVerticle(new NodeServer(config.httpPort(), zoneState, config.reservationTimeoutMs(), vehicleRepository, lifecycleManager, emergencyDispatcher)) // avvia il server http (prima ricostruisce quanto è accaduto durante il crash)
                .onSuccess(deploymentId -> System.out.println("Node started for zone " + myZoneId.value() + " (row=" + config.zoneRow() + ", col=" + config.zoneCol() + ")" + " listening on port " + config.httpPort()))
                .onFailure(err -> {
                    System.err.println("Node failed to start: " + err.getMessage());
                    System.exit(1);
                });
    }
}