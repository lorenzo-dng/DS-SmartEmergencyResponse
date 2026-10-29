package node.zone;

// esito della richiesta di un initiator di accettare l'arrivo sulla scena di un veicolo prestato
public enum ArrivalOutcome {
    ADMITTED, // l'arrivo viene accettato e registrato
    ALREADY_COMPLETED, // la scadenza dell'intervento del veicolo è già stata fissata, quindi l'arrivo è un duplicato
    REJECTED // il veicolo non serve (emergenza non più aperta o slot già coperto)
}