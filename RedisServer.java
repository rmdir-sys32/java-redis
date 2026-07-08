import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisServer {
    private final int port;
    private final ExecutorService threadPool;
    private final Database db;

    public RedisServer(int port, Database db) {
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
        this.db = db;
    }

    public void start() {
        System.out.println("Starting java redis server on port " + port + "...");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println(
                            "Accepted connection from : " + clientSocket.getRemoteSocketAddress());
                    threadPool.submit(new ClientHandler(clientSocket));

                } catch (IOException e) {
                    System.err.println("Error Connecting client connection: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to start server:" + e.getMessage());
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket clienSocket;

        public ClientHandler(Socket clientSocket) {
            this.clienSocket = clientSocket;
        }

        @Override
        public void run() {
            try (Socket socket = clienSocket) {
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                RespParser.RespReader reader = new RespParser.RespReader(inputStream);

                while (true) {
                    try {
                        RespValue request = reader.readValue();
                        RespValue response = handleRequest(request);
                        outputStream.write(response.serialize());
                        outputStream.flush();
                    } catch (EOFException e) {
                        break;
                    } catch (IOException e) {
                        System.err.println("Protocol error on " + socket.getRemoteSocketAddress()
                                + ": " + e.getMessage());
                        RespValue errorResponse =
                                new RespValue(Type.ERROR, "ERR " + e.getMessage());
                        outputStream.write(errorResponse.serialize());
                        outputStream.flush();
                        break;
                    }
                }
                System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
            } catch (IOException e) {

                System.err.println("Socket error: " + e.getMessage());
            }
        }

        private RespValue handleRequest(RespValue request) {
            if (request.type != Type.ARRAY) {
                return new RespValue(Type.ERROR, "ERR expected command array");
            }

            @SuppressWarnings("unchecked")
            List<RespValue> elements = (List<RespValue>) request.value;
            if (elements == null || elements.isEmpty()) {
                return new RespValue(Type.ERROR, "ERR empty command");
            }
            RespValue cmdElement = elements.get(0);
            if (cmdElement.type != Type.BULK_STRING) {
                return new RespValue(Type.ERROR, "ERR command name must be a bulk string");
            }
            String commandName = (String) cmdElement.value;
            try {
                return db.executeCommand(commandName, elements);
            } catch (Exception e) {
                return new RespValue(Type.ERROR, "ERR executing command " + e.getMessage());
            }
        }
    }

}


