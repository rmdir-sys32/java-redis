import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class Aof {
    private final File file;
    private final BufferedOutputStream out;

    Aof(String filepath) throws IOException {
        this.file = new File(filepath);
        // Open file in append mode (true) and wrap in a buffered stream for performance
        this.out = new BufferedOutputStream(new FileOutputStream(file, true));
    }


    /**
     * Appends a command represented as a RespValue (typically an ARRAY) to the log file
     */
    public synchronized void write(RespValue command) {
        try {
            out.write(command.serialize());
            out.flush();
        } catch (IOException e) {
            System.err.println("Failed to write command to AOF : " + e.getMessage());
        }
    }

    /**
     * Replays the AOF file on startup to restore the database state
     */
    public void replay(Database db) {
        if (!file.exists()) {
            System.out.println("No AOF file found. Starting with an empty database");
            return;
        }
        System.out.println("Found AOF file. Replaying commmands to recover data...");

        try (FileInputStream in = new FileInputStream(file)) {
            RespParser.RespReader reader = new RespParser.RespReader(in);
            int commandCount = 0;


            while (true) {
                try {
                    RespValue cmd = reader.readValue();
                    if (cmd.type == Type.ARRAY) {
                        @SuppressWarnings("unchecked")
                        List<RespValue> elements = (List<RespValue>) cmd.value;
                        String cmdName = (String) elements.get(0).value;
                        // Replay commands without writing it back to the AOF
                        db.executeCommandInternal(cmdName, elements, false);
                        commandCount++;
                    }

                } catch (java.io.EOFException e) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error replaying AOF file: " + e.getMessage());
        }
    }

    public synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
    }

}
