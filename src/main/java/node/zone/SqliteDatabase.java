package node.zone;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// punto unico di accesso al file SQLite del nodo: permette di eseguire più scritture (anche su repository diversi) in un'unica transazione
public class SqliteDatabase {

    private final String jdbcUrl;

    public SqliteDatabase(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    // apre una nuova connessione (con autocommit attivo: ogni scrittura è subito definitiva)
    public Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    // blocco di scritture da eseguire sulla stessa connessione
    @FunctionalInterface
    public interface TransactionBody {
        void run(Connection conn) throws SQLException;
    }

    // esegue tutte le scritture del blocco in un'unica transazione: se il nodo crasha prima del commit, SQLite le scarta tutte al riavvio
    public void inTransaction(TransactionBody body) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false); // disattiva il commit automatico: il commit avviene solo alla fine del blocco
            try {
                body.run(conn); // scrive sulla connessione della transizione ogni tipo scrittura passata come parametro (body)
                conn.commit(); // poi salva effettivamente tutto nel db
            } catch (SQLException | RuntimeException e) { // se viene sollevata qualche eccezione
                conn.rollback(); // annulla le scritture già fatte nel blocco - tutti gli aggiornamenti o nessuno
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run database transaction: " + e.getMessage(), e);
        }
    }
}