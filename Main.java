import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) {
        int port = 6379;

        System.out.println("Starting Java Redis Server on port " + port + "...");

        // Creates a cached threadPool fto handle connections concurrently
        ExecutorService threadPool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            // Set SO_REUSEADDR so we can quickly restart the server without "Address already in
            // use" error
            serverSocket.setReuseAddress(true);

            System.out.println("Server is listening and ready to accept connections!");


            while (true) {
                try {
                    // accept() blocks until a client connects
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Accepted a new connection from: "
                            + clientSocket.getRemoteSocketAddress());


                    // offload the client connection handling to the thread pool
                    threadPool.submit(() -> handleClient(clientSocket));



                } catch (IOException e) {
                    // TODO: handle exception
                    System.out.println("Error handling client connection" + e.getMessage());
                }

            }
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        } finally {
            // always shut down the thread pool when the server finishes
            threadPool.shutdown();
        }
    }


    /*
     * Handle the interaction loop for a single client connection Running inside a separate worker
     * thread from a thread pool
     */
    private static void handleClient(Socket clientSocket) {

        // Try with resources here ensures that clientSocket is closed automatically when leaving
        // this
        // block
        try (Socket socket = clientSocket) {
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();

            byte[] buffer = new byte[1024];
            int bytesRead;
            // Read loop for this specific client
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                String received = new String(buffer, 0, bytesRead);

                // Log the thread name along with the received data to confirm concurrency
                System.out.println("[" + Thread.currentThread().getName() + "] Received"
                        + received.replace("\r", "\\r").replace("\n", "\\n"));


                // Respond with PONG
                outputStream.write("+PONG\r\n".getBytes());
                outputStream.flush();

            }
            System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());


        } catch (IOException e) {
            // TODO: handle exception
            System.out.println("Error handling client" + clientSocket.getRemoteSocketAddress());
        }
    }
}

