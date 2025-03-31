import java.io.IOException;

public class App {
    public static void main(String[] args) {
        final Ollama test = new Ollama("llama3.2");
        try {
            test.prompt("This is a test. Is it working?");
            System.out.println(test.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    
}
