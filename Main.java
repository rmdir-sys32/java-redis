import java.io.IOException;

public class Main {


    public static void main(String args[]) {
        int port = 6379;

        Database db = new Database();
        try {
            Aof aof = new Aof("appendonly.aof");

            db.setAof(aof);

            aof.replay(db);

        } catch (IOException e) {
            System.err.println("Fatal: Failed to initialize AOF logging. " + e.getMessage());
            System.exit(1);
        }
        RedisServer server = new RedisServer(port, db);
        server.start();
    }
}
