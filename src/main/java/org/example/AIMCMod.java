// src/main/java/org/example/AIMCMod.java
package org.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.ClientModInitializer; // <--- ADD THIS IMPORT
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

// IMPLEMENT ClientModInitializer as well
public class AIMCMod implements ModInitializer, ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("tavishai"); // Changed logger name to 'tavishai'

    private static final String OLLAMA_API_URL = "http://localhost:11434/api/generate";
    private static final String OLLAMA_MODEL = "llama3";

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Tavish AI Mod!");
        // Server-side initialization logic (if any) goes here.
        // For a client-side mod like this, most logic will be in onInitializeClient.
    }

    @Override
    public void onInitializeClient() { // <--- ADD THIS METHOD FOR CLIENT-SIDE INITIALIZATION
        LOGGER.info("Initializing Tavish AI Mod Client!");

        // Register a client-side chat message listener
        ClientReceiveMessageEvents.CHAT.register((message, overlay) -> {
            String messageContent = message.getString();
            if (!overlay && messageContent.startsWith("!ai ")) {
                String prompt = messageContent.substring("!ai ".length()).trim();

                sendPromptToOllama(prompt).thenAccept(response -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.player != null) {
                        client.execute(() -> {
                            handleOllamaResponse(response);
                        });
                    }
                }).exceptionally(e -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.player != null) {
                        client.execute(() -> {
                            client.player.sendMessage(Text.literal("§cError contacting AI: " + e.getMessage()), false);
                        });
                    }
                    LOGGER.error("Failed to get response from Ollama", e);
                    return null;
                });
                return;
            }
        });
    }

    /**
     * Sends a prompt to the Ollama API and returns the AI's response.
     * This method runs asynchronously to avoid freezing the game client.
     *
     * @param prompt The natural language prompt from the player.
     * @return A CompletableFuture that will contain the AI's response string.
     */
    private CompletableFuture<String> sendPromptToOllama(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            HttpClient client = HttpClient.newHttpClient();
            Gson gson = new Gson();

            String requestBody = gson.toJson(new OllamaRequest(
                    OLLAMA_MODEL,
                    "You are an AI assistant for Minecraft. Your goal is to help the player by translating their natural language requests into valid Minecraft commands or Baritone commands. If the request is a general knowledge question about Minecraft, provide a concise answer. If the request can be translated into a command, output *only* the command string (e.g., '/give @s diamond_sword' or '#goto base'). Otherwise, say you don't understand. Prioritize Baritone commands if applicable. Always give a command if one is appropriate, do not add any additional text.",
                    prompt,
                    false
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_API_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                    if (jsonResponse.has("response")) {
                        return jsonResponse.get("response").getAsString().trim();
                    } else if (jsonResponse.has("message") && jsonResponse.getAsJsonObject("message").has("content")) {
                        return jsonResponse.getAsJsonObject("message").get("content").getAsString().trim();
                    } else if (jsonResponse.has("text")) {
                        return jsonResponse.get("text").getAsString().trim();
                    }
                    LOGGER.warn("Ollama response did not contain expected 'response' or 'message.content' field: {}", response.body());
                    return "AI did not provide a clear response.";
                } else {
                    LOGGER.error("Ollama API returned non-200 status: {} - {}", response.statusCode(), response.body());
                    return "AI API Error: Status " + response.statusCode();
                }
            } catch (Exception e) {
                LOGGER.error("Error communicating with Ollama API", e);
                return "Failed to connect to AI: " + e.getMessage();
            }
        });
    }

    private void handleOllamaResponse(String ollamaResponse) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        if (ollamaResponse.startsWith("/")) {
            client.player.networkHandler.sendChatCommand(ollamaResponse.substring(1));
            client.player.sendMessage(Text.literal("§aExecuted Minecraft command: " + ollamaResponse), false);
        } else if (ollamaResponse.startsWith("#")) {
            client.player.sendMessage(Text.literal(ollamaResponse));
            client.player.sendMessage(Text.literal("§aExecuted Baritone command: " + ollamaResponse), false);
        } else {
            client.player.sendMessage(Text.literal("§bAI: " + ollamaResponse), false);
        }
    }

    private static class OllamaRequest {
        String model;
        String prompt;
        String system;
        boolean stream;

        public OllamaRequest(String model, String system, String prompt, boolean stream) {
            this.model = model;
            this.system = system;
            this.prompt = prompt;
            this.stream = stream;
        }
    }
}
