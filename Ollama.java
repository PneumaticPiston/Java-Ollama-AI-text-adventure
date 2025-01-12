import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class for connecting to a locally running Ollama instance.
 * Supports pulling models, setting system instructions, and generating text.
 */
public class Ollama {
    private static final String BASE_URL = "http://localhost:11434/api";
    private String systemInstructions;
    private String model;
    private List<String> contextMessages;

    /**
     * Constructor with default model and no system instructions.
     * @param model The Ollama model to use (e.g., "llama2", "mistral")
     */
    public Ollama(String model) {
        this.model = model;
        this.systemInstructions = "";
        this.contextMessages = new ArrayList<>();
    }

    /**
     * Constructor with system instructions as a string.
     * @param model The Ollama model to use
     * @param systemInstructions Initial system instructions
     */
    public Ollama(String model, String systemInstructions) {
        this.model = model;
        this.systemInstructions = systemInstructions;
        this.contextMessages = new ArrayList<>();
    }

    /**
     * Constructor with system instructions from a file.
     * @param model The Ollama model to use
     * @param instructionFilePath Path to the file containing system instructions
     * @throws IOException If there's an error reading the file
     */
    public Ollama(String model, Path instructionFilePath) throws IOException {
        this.model = model;
        this.systemInstructions = readFile(instructionFilePath);
        this.contextMessages = new ArrayList<>();
    }

    /**
     * Pull the specified model from Ollama.
     * @return Response from the Ollama server
     * @throws IOException If there's a network or connection error
     */
    public String pullModel() throws IOException {
        URL url = new URL(BASE_URL + "/pull");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String payload = String.format("{\"name\": \"%s\"}", model);
        
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(payload);
            writer.flush();
        }

        return readResponse(conn);
    }

    /**
     * Generate text based on the given prompt.
     * @param prompt The user's prompt
     * @return Generated text response
     * @throws IOException If there's a network or connection error
     */
    public String generateText(String prompt) throws IOException {
        URL url = new URL(BASE_URL + "/generate");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        // Construct payload with system instructions if provided
        String payload = constructPayload(prompt);
        
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(payload);
            writer.flush();
        }

        String response = readResponse(conn);
        // Update context messages
        updateContextMessages(prompt, response);
        return response;
    }

    /**
     * Set new system instructions from a string.
     * @param instructions New system instructions
     */
    public void setSystemInstructions(String instructions) {
        this.systemInstructions = instructions;
    }

    /**
     * Set new system instructions from a file.
     * @param instructionFilePath Path to the file containing new system instructions
     * @throws IOException If there's an error reading the file
     */
    public void setSystemInstructionsFromFile(Path instructionFilePath) throws IOException {
        this.systemInstructions = readFile(instructionFilePath);
    }

    /**
     * Change the current model.
     * @param newModel The new model to use
     */
    public void setModel(String newModel) {
        this.model = newModel;
    }

    /**
     * Clear the conversation context.
     */
    public void clearContext() {
        this.contextMessages.clear();
    }

    /**
     * Read contents of a file to a string.
     * @param path Path to the file
     * @return File contents as a string
     * @throws IOException If there's an error reading the file
     */
    private String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * Construct the JSON payload for the generation request.
     * @param prompt The user's prompt
     * @return Constructed JSON payload
     */
    private String constructPayload(String prompt) {
        return String.format("{" +
            "\"model\": \"%s\"," +
            "\"prompt\": \"%s\"," +
            "\"system\": \"%s\"," +
            "\"stream\": false" +
            "}", 
            model, 
            escapeJson(prompt), 
            escapeJson(systemInstructions)
        );
    }

    /**
     * Read the response from an HTTP connection.
     * @param conn The HTTP connection
     * @return Response as a string
     * @throws IOException If there's a reading error
     */
    private String readResponse(HttpURLConnection conn) throws IOException {
        int responseCode = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    responseCode > 299 ? conn.getErrorStream() : conn.getInputStream(), 
                    StandardCharsets.UTF_8
                )
        )) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    /**
     * Update context messages for maintaining conversation state.
     * @param prompt The user's prompt
     * @param response The model's response
     */
    private void updateContextMessages(String prompt, String response) {
        contextMessages.add("{\"role\": \"user\", \"content\": \"" + escapeJson(prompt) + "\"}");
        contextMessages.add("{\"role\": \"assistant\", \"content\": \"" + escapeJson(response) + "\"}");
    }

    /**
     * Escape JSON special characters.
     * @param input The input string to escape
     * @return Escaped string
     */
    private String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    /**
     * Example usage method.
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        try {
            // Example of using the OllamaConnector
            Ollama connector = new Ollama("llama2", "You are a helpful assistant.");
            
            // Pull the model first
            System.out.println("Pulling model: " + connector.pullModel());
            
            // Generate a response
            String response = connector.generateText("Tell me a short joke.");
            System.out.println("Response: " + response);
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}