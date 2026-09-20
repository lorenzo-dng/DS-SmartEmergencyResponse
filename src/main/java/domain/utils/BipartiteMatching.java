package domain.utils;

import blogspot.software_and_algorithms.stern_library.optimization.HungarianAlgorithm;

/*
Implementazione dell'algoritmo Ungherese per l'assegnazione dei veicoli alle emergenze - minimizza il costo totale di assegnazione.

L'algoritmo riceve una matrice dei costi in cui:
    - ogni riga rappresenta un veicolo disponibile;
    - ogni colonna rappresenta uno slot di un'emergenza: uno slot rappresenta un singolo posto da coprire all'interno di un'emergenza (stessa emergenza o emergenza diversa).
    - ogni cella (costMatrix[i][j]) rappresenta il costo di assegnare il veicolo i allo slot j.

Esempio:
         Slot0 (E1)  Slot1 (E1)  Slot2 (E2)
V0           6           5           8
V1           3           6           2
V2           9           9           4

Soluzione:
    - V0 -> Slot1 (E1): costo 5
    - V1 -> Slot0 (E2): costo 3
    - V2 -> Slot2 (E2): costo 4
=> costo totale = 5 + 3 + 4 = 12
N.B: vengono confrontate tutte le minime combinazioni di associazioni (infatti, ricordando che uno slot può essere occupato solo da un veicolo, non è l'unica soluzione possibile di assegnazione dei veicoli)

Il risultato viene restituito come array di slot: assignment = [1, 0, 2]:
Se un veicolo non viene assegnato, il valore restituito è -1.
*/
public class BipartiteMatching {

    public static int[] solve(double[][] costMatrix) {
        return new HungarianAlgorithm(costMatrix).execute();
    }
}