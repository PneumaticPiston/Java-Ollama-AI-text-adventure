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

    // Session context to store file contents
    private List<String> sessionFiles = new ArrayList<>();

    /**
     * Add a file to the session context.
     * Reads the file contents and stores them for potential reference in future prompts.
     * 
     * @param filePath Path to the file to be added to the session
     * @return The contents of the added file
     * @throws IOException If there's an error reading the file
     */
    public String addFileToContext(Path filePath) throws IOException {
        // Read the file contents
        String fileContents = readFile(filePath);
        
        // Add the file contents to the session files list
        sessionFiles.add(String.format("File '%s' contents:\n%s", 
            filePath.getFileName().toString(), 
            fileContents)
        );
        
        return fileContents;
    }

    /**
     * Get all files currently in the session context.
     * 
     * @return List of file contents added to the session
     */
    public List<String> getSessionFiles() {
        return new ArrayList<>(sessionFiles);
    }

    /**
     * Clear all files from the session context.
     */
    public void clearSessionFiles() {
        sessionFiles.clear();
    }

    /**
     * Generate text using the Ollama API.
     * 
     * @param prompt The user's prompt
     * @return Generated text response
     * @throws IOException If there's a network or connection error
     */
    public String generateText(String prompt) throws IOException {
        // Prepare the context by combining session files and system instructions
        StringBuilder fullContext = new StringBuilder(systemInstructions);
        
        // Add session files to the context if any exist
        for (String fileContext : sessionFiles) {
            fullContext.append("\n\n").append(fileContext);
        }
        
        // Prepare the request
        URL url = new URL(BASE_URL + "/generate");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        // Construct payload with full context
        String payload = String.format("{" +
            "\"model\": \"%s\"," +
            "\"prompt\": \"%s\"," +
            "\"system\": \"%s\"," +
            "\"stream\": false" +
            "}", 
            model, 
            escapeJson(prompt), 
            escapeJson(fullContext.toString())
        );

        // Send request
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(payload);
            writer.flush();
        }

        // Get and return response
        String response = readResponse(conn);
        updateContextMessages(prompt, extractGeneratedText(response));
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
     * Extract the generated text from the full JSON response.
     * @param jsonResponse The full JSON response from Ollama
     * @return The extracted generated text
     */
    public String extractGeneratedText(String jsonResponse) {
        // Regular expression to extract the 'response' value from JSON
        Pattern pattern = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(jsonResponse);
        
        if (matcher.find()) {
            // Unescape the JSON string
            return unescapeJson(matcher.group(1));
        }
        
        // Return the original response if no match found
        return jsonResponse;
    }

    /**
     * Generates text and returns only the generated content.
     * @param prompt The user's prompt
     * @return Extracted generated text
     * @throws IOException If there's a network or connection error
     */
    public String generateCleanText(String prompt) throws IOException {
        String fullResponse = generateText(prompt);
        return extractGeneratedText(fullResponse);
    }

	public String respond(String prompt) throws IOException {
        String fullResponse = generateText(prompt);
        return extractGeneratedText(fullResponse);
	}

	public String prompt(String prompt) throws IOException {
        String fullResponse = generateText(prompt);
        return extractGeneratedText(fullResponse);
	}
    /**
     * Unescape JSON special characters.
     * @param input The escaped input string
     * @return Unescaped string
     */
    private String unescapeJson(String input) {
        return input.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
    }

    /**
     * Example usage method.
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        try {
            // Example of using the Ollama
            Ollama connector = new Ollama("llama3.2", "You are a helpful assistant.");
            
            // Pull the model first
            System.out.println("Pulling model: " + connector.pullModel());
            
            // Generate a clean response
            String cleanResponse = connector.generateCleanText("Tell me a short joke.");
            System.out.println("Clean Response: " + cleanResponse);
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}