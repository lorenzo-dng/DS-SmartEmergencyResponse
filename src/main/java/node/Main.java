package node;

import communication.NeighborHealth;
import communication.contractnet.ContractNetNodeClient;
import domain.zone.Zone;
import domain.zone.ZoneId;
import io.vertx.core.Vertx;
import node.config.NodeConfig;
import node.server.NodeServer;
import node.topology.ZoneGridFactory;
import node.vehicle.VehiclePopulator;
import node.zone.ZoneState;
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
        ZoneState zoneState = new ZoneState(myZone); //inizializza lo stato della zona
        VehiclePopulator.populate(zoneState, Path.of(config.vehiclesConfigPath())); // popola i veicoli iniziali della zona

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new NodeServer(config.httpPort(), zoneState, config.reservationTimeoutMs())) // avvia il server http
                .onSuccess(deploymentId -> { // se ha avuto successo
                    System.out.println("Node started for zone " + myZoneId.value() + " (row=" + config.zoneRow() + ", col=" + config.zoneCol() + ")" + " listening on port " + config.httpPort());
                    NeighborHealth neighborHealth = new NeighborHealth(myZone.getNeighbors());
                    ContractNetNodeClient contractNetClient = new ContractNetNodeClient(vertx, config::addressFor, neighborHealth, config.neighborTimeoutMs());
                    // TODO: usare contractNetClient.callForProposals(...) nella logica di gestione emergenze quando un evento resta senza veicolo locale disponibile
                })
                .onFailure(err -> { // se ha fallito
                    System.err.println("Node failed to start: " + err.getMessage());
                    System.exit(1); // termina il thread vert
                });
    }
}