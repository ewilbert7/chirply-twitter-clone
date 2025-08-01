import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.logging.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * Server for the Chirply application
 */
public class ChirpServer {
    private static final Logger logger = Logger.getLogger(ChirpServer.class.getName()); // for logging

    private int port;
    private String documentRoot;
    private Store store;
    private LinkedBlockingQueue<ClientRequest> requestQueue;
    private static final int WORKER_COUNT = 4;
    private Map<String, Integer> federatedServers;
    private Set<String> blackList;

    /**
     * Constructor to initialise values
     * 
     * @param port         the port of the server
     * @param documentRoot the root directory where the files to be served are kept
     * @param servers      the comma-seperated list of servers to federate against
     */
    public ChirpServer(int port, String documentRoot, String servers) {
        this.port = port;
        this.documentRoot = documentRoot;
        this.store = new Store();
        this.requestQueue = new LinkedBlockingQueue<>();
        this.blackList = new HashSet<>();
        this.federatedServers = loadFederatedServers(servers);
        loadChirpsFromStore();
    }

    /**
     * Static block to initialise logger
     */
    static {

        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.ALL);
        logger.addHandler(consoleHandler);
        logger.setLevel(Level.ALL);
    }

    /**
     * Starts the server which enables it to accept client requests
     */
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.info("Server started on port " + port);

            for (int i = 0; i < WORKER_COUNT; i++) {
                new Thread(new RequestHandler(requestQueue, this)).start();
            }

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("\nClient connected: " + clientSocket.getRemoteSocketAddress());

                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                String line = reader.readLine();
                if (line == null) {
                    continue;
                }
            
                String[] requestParts = line.split(" ");
                String method = requestParts[0];
                String requestUri = requestParts[1];
                String headers = readHeaders(reader);
                String requestBody = readRequestBody(reader, headers);
                
                requestQueue.add(new ClientRequest(clientSocket, method, requestUri, headers, requestBody));
                logger.info("Queued request: " + method + requestUri);
            }
        } catch (IOException e) {
            logger.severe("Error starting server: " + e.getMessage());
        }
    }

    /**
     * Processes a client request
     * 
     * @param request the ClientRequest to be processed
     */
    public void processRequest(ClientRequest request) {
        try (Socket clientSocket = request.getSocket()) {
            logger.info("Processing request from: " + clientSocket.getRemoteSocketAddress());

            PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);

            // Get request details
            String method = request.getMethod();
            String requestUri = request.getRequestUri();
            String requestBody = request.getRequestBody();
            String viaHeader = extractViaHeader(request.getHeaders());

            logger.fine("Request details: Method=" + method + ", URI=" + requestUri
                    + (viaHeader.equals("") ? "" : ", Via: " + viaHeader));

            // Handles request according to method
            switch (method) {
                case "GET":
                    switch (requestUri) {
                        case "/":
                            serveFile(writer, "index.html");
                            break;
                        case "/chirps":
                            System.out.println(viaHeader);
                            serveChirps(writer, viaHeader);
                            break;
                        default:
                            if (requestUri.startsWith("/")) {
                                serveFile(writer, requestUri.substring(1)); // Remove leading slash
                            } else {
                                sendErrorResponse(writer, 404, "Not Found");
                            }
                            break;
                    }
                    break;

                case "POST":
                    switch (requestUri) {
                        case "/chirps":
                            handlePostChirp(writer, requestBody);
                            break;
                        default:
                            sendErrorResponse(writer, 404, "Not Found");
                            break;
                    }
                    break;

                case "DELETE":
                    if (requestUri.startsWith("/chirps/")) {
                        try {
                            int chirpId = Integer.parseInt(requestUri.split("/")[2]);
                            deleteChirp(writer, chirpId);
                        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                            sendErrorResponse(writer, 400, "Invalid Chirp ID");
                        }
                    } else {
                        sendErrorResponse(writer, 404, "Not Found");
                    }
                    break;
                case "PUT":
                    if (requestUri.startsWith("/chirps/")) {
                        try {
                            int chirpId = Integer.parseInt(requestUri.split("/")[2]);
                            editChirp(writer, chirpId, requestBody);
                        } catch (Exception e) {
                            sendErrorResponse(writer, 500, "Server Error");
                        }
                    }
                    break;
                case "OPTIONS":
                    handleOptions(writer);
                    break;
                default:
                    sendErrorResponse(writer, 405, "Method Not Allowed");
                    break;
            }
            writer.close();
        } catch (IOException e) {
            logger.severe("Error handling request: " + e.getMessage());
        }
    }

    /**
     * Serves a file in the root directory
     * 
     * @param writer   PrintWriter to return response to client
     * @param filePath file path of the file requested
     */
    private void serveFile(PrintWriter writer, String filePath) {

        File file = new File(documentRoot, filePath);

        // Ensure that file exists and is file
        if (file.exists() && file.isFile()) {
            try {
                byte[] fileContent = Files.readAllBytes(file.toPath());
                String fileType = file.getName().split("\\.")[1]; // get the file type

                writer.println("HTTP/1.1 200 OK");
                writer.println("Content-Type: " + getContentType(fileType));
                writer.println();
                writer.write(new String(fileContent));
                writer.flush();

                logger.info("\nServer Response:\nStatus: 200 OK\nContent-Type: " + getContentType(fileType)
                        + "\nServed file: " + filePath + "\n");
            } catch (IOException e) {
                sendErrorResponse(writer, 500, "Internal Server Error");
            }
        } else {
            logger.info("File not found: " + file.getPath());
            sendErrorResponse(writer, 404, "Not Found");
        }
    }

    /**
     * Serves combined list of chirps from this server and those in the
     * federated server list
     * 
     * @param writer    writer to send response to client
     * @param viaHeader "Via: " header to prevent federated loops
     * @throws UnknownHostException
     */
    private void serveChirps(PrintWriter writer, String viaHeader) throws UnknownHostException {
        JsonArrayBuilder allChirpsArrayBuilder = Json.createArrayBuilder();
        store.getAllChirps().forEach(chirp -> allChirpsArrayBuilder.add(chirp.toJsonObject()));

        Set<String> viaHeaderSet = new HashSet<>(Arrays.asList(viaHeader.split(",")));

        for (Map.Entry<String, Integer> serverEntry : federatedServers.entrySet()) {
            String server = serverEntry.getKey() + ":" + serverEntry.getValue();

            if (viaHeaderSet.contains(server) || blackList.contains(server)) {
                continue;
            }

            String myHostNameAndPort = InetAddress.getLocalHost().getHostName() + ":" + port;
            String hostname = myHostNameAndPort.split(":")[0];
            try {
                // Create socket connection to federated server
                String[] serverParts = server.split(":");
                String host = serverParts[0];
                int port = Integer.parseInt(serverParts[1]);

                try (Socket socket = new Socket(host, port);
                        PrintWriter socketWriter = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader socketReader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream()))) {
                    logger.info("Initialized socket to " + socket.getInetAddress());

                    logger.info("Host: " + host);
                    logger.info("Port: " + port);

                    blackList.add(host + ":" + port);

                    // Send HTTP GET request to the federated server
                    socketWriter.println("GET /chirps HTTP/1.1");
                    socketWriter.println("Host: " + hostname);
                    socketWriter.println("Accept: application/json");
                    socketWriter.println("Via: " + myHostNameAndPort + (viaHeader.isEmpty() ? "" : ", "+ viaHeader));
                    socketWriter.println("Connection: close");
                    socketWriter.println();

                    logger.info("\nRequest Details:\nMethod: GET /chirps HTTP/1.1\nAccept: application/json\nVia: "
                            + myHostNameAndPort + (viaHeader.isEmpty() ? "" : ", "+ viaHeader) + "\nConnection: close\n");

                    // Read the response from the federated server
                    String line;
                    StringBuilder responseBuilder = new StringBuilder();
                    while ((line = socketReader.readLine()) != null) {
                        responseBuilder.append(line).append("\n");
                    }

                    String response = responseBuilder.toString();
                    if (response.contains("200 OK")) {
                        String jsonResponse = response.split("\n\n", 2)[1]; // Get the body after the headers
                        JsonReader jsonReader = Json.createReader(new StringReader(jsonResponse));
                        JsonObject chirpsJson = jsonReader.readObject();
                        chirpsJson.getJsonArray("chirps").forEach(jsonValue -> {
                            JsonObject chirpJson = jsonValue.asJsonObject();
                            allChirpsArrayBuilder.add(chirpJson);
                        });
                    } else {
                        allChirpsArrayBuilder.add(generateErrorChirp("Error fetching chirps from " + server));
                    }

                } catch (Exception e) {
                    allChirpsArrayBuilder.add(generateErrorChirp("Failed to connect to server: " + server));
                }
            } catch (Exception e) {
                allChirpsArrayBuilder.add(generateErrorChirp("Failed to connect to server: " + server));
            }
        }

        JsonObject response = Json.createObjectBuilder()
                .add("chirps", allChirpsArrayBuilder)
                .build();

        sendChirpsResponse(writer, response);
    }

    /**
     * Sends chirps response to the client
     * 
     * @param writer           writer to send response to the clietn
     * @param chirpsJsonObject the JSON array of all the chirps
     */
    private void sendChirpsResponse(PrintWriter writer, JsonObject chirpsJsonObject) {
        writer.println("HTTP/1.1 200 OK");
        writer.println("Content-Type: application/json");
        writer.println();
        writer.write(chirpsJsonObject.toString());
        writer.flush();

        logger.info("\nServer Response:\nStatus: 200 OK\nContent-Type: application/json\n");
        blackList.removeAll(blackList);
    }

    /**
     * Generates error chirp if query is unsuccessful with the federated servers
     * 
     * @param errorMessage the error message to be displayed
     * @return a json object with the error message as the content
     */
    private JsonObject generateErrorChirp(String errorMessage) {
        return Json.createObjectBuilder()
                .add("username", "SYSTEM")
                .add("content", errorMessage)
                .build();
    }

    /**
     * Loads all the chirps from the store
     */
    private void loadChirpsFromStore() {
        try {
            File chirpsFile = new File(documentRoot, "chirps.json");
            if (chirpsFile.exists()) {
                String jsonString = new String(Files.readAllBytes(chirpsFile.toPath()));
                store.addFromJson(jsonString);
                System.out.println("Chirps loaded from JSON file.");
            } else {
                System.out.println("No chirps.json file found. Starting with an empty store.");
            }
        } catch (IOException e) {
            System.out.println("Error loading chirps from file: " + e.getMessage());
            logger.severe("Error loading chirps from file: " + e.getMessage());
        }
    }

    /**
     * Loads the federated servers to a Map
     * 
     * @param servers comma-seperated String form of the servers
     * @return a Map containing the server hostname as the key and its port as the
     *         value
     */
    private Map<String, Integer> loadFederatedServers(String servers) {
        Map<String, Integer> federatedServers = new HashMap<>();
        for (String server : servers.replace("\"", "").split(",")) {
            String[] values = server.split(":");
            federatedServers.put(values[0], Integer.parseInt(values[1]));
        }
        logger.info("Loaded federated servers: " + federatedServers);
        return federatedServers;
    }

    /**
     * Extracts the via header from the headers
     * 
     * @param headers the headers parsed from a request
     * @return the Via Header of an empty string if no "Via: " line is found
     */
    private String extractViaHeader(String headers) {
        return headers.lines()
                .filter(header -> header.startsWith("Via:"))
                .map(header -> header.substring(4).trim()) // Removes the "Via:" part of the header and whitespace
                .findFirst()
                .orElse("");
    }

    /**
     * Adds a chirp to the store
     * 
     * @param writer      writer to send response back to client
     * @param requestBody String form of the request body with the attributes of the
     *                    chirp
     */
    private void handlePostChirp(PrintWriter writer, String requestBody) {
        try {
            JsonReader reader = Json.createReader(new StringReader(requestBody));
            JsonObject chirpJson = reader.readObject();

            String username = chirpJson.getString("username", "").trim();
            String content = chirpJson.getString("content", "").trim();

            if (username.isEmpty() || content.isEmpty()) {
                sendErrorResponse(writer, 400, "Bad Request: Invalid Input");
                return;
            }

            store.addChirp(username, content);

            Chirp newChirp = store.getChirp(store.findNextChirpId() - 1);
            JsonObject responseJson = newChirp.toJsonObject();

            writer.println("HTTP/1.1 201 Created");
            writer.println("Content-Type: application/json");
            writer.println();
            writer.write(responseJson.toString());
            writer.flush();
            logger.info("\nServer Response:\nStatus: 201 Created\nContent-Type: application/json\n");
        } catch (Exception e) {
            // if JSON reading fails
            sendErrorResponse(writer, 400, "Bad Request");
            ;
        }
    }

    /**
     * Read the headers from the client request
     * 
     * @param reader reader to read the request
     * @return a String form of the headers
     * @throws IOException
     */
    private String readHeaders(BufferedReader reader) throws IOException {
        StringBuilder headers = new StringBuilder();
        String line;
        String headerPattern = "^([a-zA-Z0-9-]+):\\s*(.+)$"; // regex for header
        Pattern pattern = Pattern.compile(headerPattern);

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String headerName = matcher.group(1);
                String headerValue = matcher.group(2);
                headers.append(headerName).append(":")
                        .append(headerValue).append("\n");
            } else {
                logger.warning("Invalid header format: " + line);
            }
        }
        return headers.toString();
    }

    /**
     * Extracts the request body of the header
     * 
     * @param reader  to read in the body
     * @param headers headers of the request
     * @return the request body
     * @throws IOException
     */
    private String readRequestBody(BufferedReader reader, String headers) throws IOException {
        String contentLengthHeader = headers.lines()
                .filter(header -> header.startsWith("Content-Length"))
                .findFirst()
                .orElse(null);

        if (contentLengthHeader != null) {
            int contentLength = Integer.parseInt(contentLengthHeader.split(":")[1].trim());
            char[] body = new char[contentLength];
            reader.read(body, 0, contentLength);
            return new String(body);
        }
        return "";
    }

    /**
     * Deletes a chirp from the store
     * 
     * @param writer  to send the response back to the client
     * @param chirpId the id of the chirp to be deleted
     */
    private void deleteChirp(PrintWriter writer, int chirpId) {
        Chirp chirp = store.deleteChirp(chirpId);
        if (chirp != null) {
            writer.println("HTTP/1.1 200 OK");
            writer.println("Content-Type: application/json");
            writer.println();
            writer.write("{\"message\": \"Chirp deleted successfully\"}");
            writer.flush();

            logger.info("\nServer Response:\nStatus: 200 OK\n\"Chirp deleted successfully\"\n");
        } else {
            sendErrorResponse(writer, 404, "Not Found");
        }
    }

    /**
     * Edits a chirp
     * 
     * @param writer      to send response back to the client
     * @param chirpId     id of the chirp to edit
     * @param requestBody the body containing the attributes of the chirp to change
     */
    private void editChirp(PrintWriter writer, int chirpId, String requestBody) {
        try {
            JsonReader reader = Json.createReader(new StringReader(requestBody));
            JsonObject chirpJson = reader.readObject();

            Chirp newChirp = Chirp.fromJson(chirpJson);

            store.updateChirp(chirpId, newChirp);

            writer.println("HTTP/1.1 201 Created");
            writer.println("Content-Type: application/json");
            writer.println();
            writer.write(chirpJson.toString());
            writer.flush();

            logger.info("\nServer Response:\nStatus: 201 Created\nContent-Type: application/json\n");
        } catch (Exception e) {
            sendErrorResponse(writer, 500, "Internal Server Error");
        }
    }

    /**
     * Sends an error response if anything fails
     * 
     * @param writer        to send response to clietn
     * @param statusCode    the HTTP status of the error
     * @param statusMessage the message of the error
     */
    private void sendErrorResponse(PrintWriter writer, int statusCode, String statusMessage) {
        writer.println("HTTP/1.1 " + statusCode + " " + statusMessage);
        writer.println("Content-Type: text/html");
        writer.println();
        writer.println("<html><body><h1>" + statusCode + " " + statusMessage + "</h1></body></html>");
        writer.flush();
        logger.info("\nServer Response:\nStatus: " + statusCode + " " + statusMessage);
    }

    /**
     * Helper method to dynamically generated the Content-Type header
     * 
     * @param fileType the file extension of the file being served
     * @return a String of the appropriate Content-Type
     */
    private String getContentType(String fileType) {
        return switch (fileType) {
            case "html":
                yield "text/html";
            case "js":
                yield "application/javascript";
            case "css":
                yield "text/css";
            case "json":
                yield "application/json";
            default:
                yield "";
        };
    }

    /**
     * Handles CORS preflight request from browsers while testing
     * 
     * @param writer to send response to browser
     */
    private void handleOptions(PrintWriter writer) {
        // Respond to CORS preflight request
        writer.println("HTTP/1.1 200 OK");
        writer.println("Access-Control-Allow-Origin: *");
        writer.println("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS, PUT");
        writer.println("Access-Control-Allow-Headers: Content-Type");
        writer.println();
        writer.flush();
    }

    /**
     * Main method to start the server
     * 
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // Load configuration from properties file
        Configuration config = new Configuration("cs2003-C3.properties");
        int serverPort = config.serverPort_;
        String documentRoot = config.documentRoot_;
        String federatedServers = config.federation_;

        System.out.println(federatedServers);

        // Start the server
        ChirpServer server = new ChirpServer(serverPort, documentRoot, federatedServers);
        server.start();
    }
}
