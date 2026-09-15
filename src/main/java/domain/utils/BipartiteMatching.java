package domain.utils;

import blogspot.software_and_algorithms.stern_library.optimization.HungarianAlgorithm;

// implementazione dell' algoritmo Ungherese per l'assegnazione dei veicoli alle emergenze
public class BipartiteMatching {

    // costMatrix[i][j] = costo di assegnare la riga i alla colonna j (es. veicolo i, evento j);
    // restituisce result[i] = indice di colonna assegnato alla riga i, o -1 se non assegnata
    public static int[] solve(double[][] costMatrix) {
        return new HungarianAlgorithm(costMatrix).execute();
    }
}