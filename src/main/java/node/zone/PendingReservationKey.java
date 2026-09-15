package node.zone;

import domain.emergency.EmergencyId;
import domain.vehicle.VehicleCategory;

// chiave utilizzata (dal contractor) per distinguere le richieste di veicoli su una stessa emergenza (è possibile avere un' emergenza e diverse categorie di veicoli richiesti)
// - id emergenza + categoria perche solo uno dei due non basta per tenere traccia dei veicoli richiesti per una stessa emergenza:
// Infatti, ogni richiesta di categoria veicoli viene espressa attraverso una richiesta diversa, dopo la quale il contractor mette in pending alcuni veicoli.
// senza id emergenza, una seconda richiesta della stessa categoria di veicoli ma per un'emergenza separata, sovrascriverebbe, nella lista di veicoli pending, quelli dell'emergenza precedente
// senza categoria, una seconda richiesta di una categoria diversa di veicoli della stessa emergenza, sovrascriverebbe, nella lista di veicoli pending, quelli della categoria precedente
public record PendingReservationKey(EmergencyId emergencyId, VehicleCategory category) { }