package com.example.receipt;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Blob;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class ReceiptExtractorApplication implements CommandLineRunner {

    private static final Logger LOGGER = LogManager.getLogger(ReceiptExtractorApplication.class);
    private static final String DEFAULT_LOCATION = "us-central1";
    private static final String MODEL_NAME = "gemini-2.5-flash";
    // Generated text exports are written under ./output.
    private static final Path OUTPUT_DIRECTORY = Path.of("output");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        // Bootstrap the Spring Boot CLI application.
        SpringApplication.run(ReceiptExtractorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // Step 1: Parse required CLI arguments for project, region, image path, and credentials path.
        CliArgs cliArgs = parseArgs(args);
        LOGGER.info("Starting receipt extraction flow. projectId={}, location={}, imagePath={}",
                cliArgs.projectId(), cliArgs.location(), cliArgs.imagePath());

        // Step 2: Validate that the service-account credential file exists before calling Vertex AI.
        Path credentialsPath = Path.of(cliArgs.credentialsPath());
        if (!Files.exists(credentialsPath)) {
            throw new IllegalArgumentException("Credentials file not found: " + credentialsPath);
        }
        LOGGER.info("Credential file found at {}", credentialsPath.toAbsolutePath());

        // Step 3: Load the credential file to confirm it is readable and structurally valid.
        try (var credentialsStream = Files.newInputStream(credentialsPath)) {
            GoogleCredentials.fromStream(credentialsStream);
        }
        LOGGER.info("Credential file was loaded successfully.");

        // Step 4: Expose the credential file as ADC so the Vertex AI SDK can authenticate.
        System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", credentialsPath.toAbsolutePath().toString());
        LOGGER.debug("System property GOOGLE_APPLICATION_CREDENTIALS has been set.");

        // Step 5: Validate that the input receipt image exists.
        Path imagePath = Path.of(cliArgs.imagePath());
        if (!Files.exists(imagePath)) {
            throw new IllegalArgumentException("Image file not found: " + imagePath);
        }
        LOGGER.info("Input image found at {}", imagePath.toAbsolutePath());

        // Step 6: Read the receipt image into memory and determine the MIME type for Gemini.
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String mimeType = resolveMimeType(imagePath);
        LOGGER.info("Loaded receipt image. byteCount={}, mimeType={}", imageBytes.length, mimeType);

        // Step 7: Wrap the image bytes into an inline image part for the Vertex AI request.
        Part imagePart = Part.newBuilder()
                .setInlineData(
                        Blob.newBuilder()
                                .setMimeType(mimeType)
                                .setData(ByteString.copyFrom(imageBytes))
                                .build())
                .build();
                    LOGGER.debug("Image part was created successfully.");

                    // Step 8: Build a strict extraction prompt so the model returns raw JSON only.
        String prompt = """
                You are an information extraction engine.
                Extract data from the provided Thai receipt image.

                Return ONLY a raw JSON string.
                Do NOT wrap the response in markdown.
                Do NOT use triple backticks.
                Do NOT include explanations.

                JSON schema:
                {
                  \"storeName\": \"String\",
                  \"receiptNumber\": \"String\",
                  \"date\": \"String\",
                  \"totalAmount\": 0.0,
                  \"lineItems\": [
                    {
                      \"productName\": \"String\",
                      \"productCode\": \"String\",
                      \"quantity\": 0,
                      \"productPrice\": 0.0
                    }
                  ]
                }

                Rules:
                - Use null for missing scalar values.
                - Use an empty array [] if no line items are detected.
                - Keep keys exactly as specified.
                """;
        LOGGER.debug("Prompt prepared for receipt extraction.");

        // Step 9: Combine the prompt and image into a single user content payload.
        Content content = Content.newBuilder()
                .setRole("user")
                .addParts(Part.newBuilder().setText(prompt).build())
                .addParts(imagePart)
                .build();
        LOGGER.debug("Vertex AI content payload created.");

        // Step 10: Open the Vertex AI client, send the request, and capture the raw JSON response.
        try (VertexAI vertexAI = new VertexAI(cliArgs.projectId(), cliArgs.location())) {
            LOGGER.info("Vertex AI client initialized successfully.");

            // Step 11: Create the Gemini model instance that will process the receipt image.
            GenerativeModel model = new GenerativeModel(MODEL_NAME, vertexAI);
            LOGGER.info("Generative model initialized. modelName={}", MODEL_NAME);

            // Step 12: Execute the multimodal request against Gemini.
            GenerateContentResponse response = model.generateContent(content);
            LOGGER.info("Gemini request completed successfully.");

            // Step 13: Read token-usage metadata returned by Gemini and log it for cost/usage tracking.
            printTokenUsage(response);

            // Step 14: Extract plain text from the SDK response and parse it into Java objects.
            String rawJson = ResponseHandler.getText(response).trim();
            LOGGER.debug("Raw JSON returned by Gemini:{}{}", System.lineSeparator(), rawJson);

            ReceiptData receiptData = GSON.fromJson(rawJson, ReceiptData.class);
            LOGGER.info("Gemini JSON response parsed into ReceiptData.");

            // Step 15: Print a human-readable summary and export the same result to a text file.
            printReceiptData(receiptData, rawJson);
            exportResultAsTextFile(receiptData, rawJson);
            LOGGER.info("Receipt extraction flow finished successfully.");
        } catch (ApiException ex) {
            // Step 16: Translate model availability errors into a more actionable log message.
            if (ex.getStatusCode() != null && ex.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                LOGGER.error("Model not found or not accessible in this project/location. modelName={}, projectId={}, location={}",
                        MODEL_NAME, cliArgs.projectId(), cliArgs.location(), ex);
                LOGGER.error("Try a model or region that the project can access.");
                return;
            }

            LOGGER.error("Vertex AI request failed unexpectedly.", ex);
            throw ex;
        }
    }

    private static void printReceiptData(ReceiptData receiptData, String rawJson) {
        // Step 17: If parsing failed, log the raw payload to help with debugging prompt issues.
        if (receiptData == null) {
            LOGGER.warn("Model response was not valid JSON for ReceiptData.");
            LOGGER.warn("Raw response:{}{}", System.lineSeparator(), rawJson);
            return;
        }

        // Step 18: Log the extracted header information for the receipt.
        LOGGER.info("==== Extracted Receipt Data ====");
        LOGGER.info("Store Name    : {}", receiptData.storeName);
        LOGGER.info("Receipt Number: {}", receiptData.receiptNumber);
        LOGGER.info("Date          : {}", receiptData.date);
        LOGGER.info("Total Amount  : {}", receiptData.totalAmount);

        List<LineItem> items = receiptData.lineItems == null ? new ArrayList<>() : receiptData.lineItems;
        LOGGER.info("Line Items    : {}", items.size());

        // Step 19: Log each extracted line item in a readable format.
        for (int i = 0; i < items.size(); i++) {
            LineItem item = items.get(i);
            LOGGER.info("  [{}]", i + 1);
            LOGGER.info("    Product Name : {}", item.productName);
            LOGGER.info("    Product Code : {}", item.productCode);
            LOGGER.info("    Quantity     : {}", item.quantity);
            LOGGER.info("    Product Price: {}", item.productPrice);
        }

        // Step 20: Log the final parsed JSON for audit and troubleshooting purposes.
        LOGGER.info("Parsed JSON (pretty):{}{}", System.lineSeparator(), GSON.toJson(receiptData));
    }

    private static void printTokenUsage(GenerateContentResponse response) {
        // Step 21: Read usage metadata from the model response and report token counts to the console logs.
        if (response == null || !response.hasUsageMetadata()) {
            LOGGER.warn("Usage metadata was not returned by the model response.");
            return;
        }

        GenerateContentResponse.UsageMetadata usageMetadata = response.getUsageMetadata();
        LOGGER.info("=== Token Usage ===");
        LOGGER.info("Input (Prompt) Tokens: {}", usageMetadata.getPromptTokenCount());
        LOGGER.info("Output (Candidates) Tokens: {}", usageMetadata.getCandidatesTokenCount());
        LOGGER.info("Total Tokens: {}", usageMetadata.getTotalTokenCount());
    }

    private static void exportResultAsTextFile(ReceiptData receiptData, String rawJson) throws IOException {
        // Step 22: Build a safe file name from the extracted store name, current timestamp, and receipt number.
        String storeName = receiptData != null && !isBlank(receiptData.storeName) ? receiptData.storeName : "UNKNOWN_STORE";
        String receiptNo = receiptData != null && !isBlank(receiptData.receiptNumber) ? receiptData.receiptNumber : "UNKNOWN_RECEIPT";
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        // Step 23: Ensure the output directory exists before writing the export file.
        Files.createDirectories(OUTPUT_DIRECTORY);
        LOGGER.debug("Output directory is ready at {}", OUTPUT_DIRECTORY.toAbsolutePath());

        String fileName = sanitizeFileNamePart(storeName) + "_" + dateTime + "_" + sanitizeFileNamePart(receiptNo) + ".txt";
        Path outputPath = OUTPUT_DIRECTORY.resolve(fileName);
        LOGGER.info("Preparing export file at {}", outputPath.toAbsolutePath());

        // Step 24: Construct the text-file content from the extracted result.
        StringBuilder content = new StringBuilder();
        content.append("==== Extracted Receipt Data ====\n");
        if (receiptData == null) {
            content.append("Model response was not valid JSON for ReceiptData.\n");
            content.append("Raw response:\n").append(rawJson).append("\n");
        } else {
            content.append("Store Name    : ").append(receiptData.storeName).append("\n");
            content.append("Receipt Number: ").append(receiptData.receiptNumber).append("\n");
            content.append("Date          : ").append(receiptData.date).append("\n");
            content.append("Total Amount  : ").append(receiptData.totalAmount).append("\n");

            List<LineItem> items = receiptData.lineItems == null ? new ArrayList<>() : receiptData.lineItems;
            content.append("Line Items    : ").append(items.size()).append("\n");
            for (int i = 0; i < items.size(); i++) {
                LineItem item = items.get(i);
                content.append("  [").append(i + 1).append("]\n");
                content.append("    Product Name : ").append(item.productName).append("\n");
                content.append("    Product Code : ").append(item.productCode).append("\n");
                content.append("    Quantity     : ").append(item.quantity).append("\n");
                content.append("    Product Price: ").append(item.productPrice).append("\n");
            }

            content.append("\nParsed JSON (pretty):\n");
            content.append(GSON.toJson(receiptData)).append("\n");
        }

        // Step 25: Write the export file to disk and log the final location.
        Files.writeString(outputPath, content.toString());
        LOGGER.info("Exported result file: {}", outputPath.toAbsolutePath());
    }

    private static String sanitizeFileNamePart(String value) {
        // Replace Windows-invalid filename characters so export files can always be created.
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "UNKNOWN" : cleaned;
    }

    private static String resolveMimeType(Path imagePath) throws IOException {
        // First ask the operating system to resolve the MIME type from the file metadata.
        String probed = Files.probeContentType(imagePath);
        if (probed != null && probed.startsWith("image/")) {
            LOGGER.debug("Detected MIME type from Files.probeContentType: {}", probed);
            return probed;
        }

        // If the OS cannot determine the MIME type, fall back to the file extension.
        String lower = imagePath.getFileName().toString().toLowerCase();
        if (lower.endsWith(".png")) {
            LOGGER.debug("Falling back to image/png based on file extension.");
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            LOGGER.debug("Falling back to image/jpeg based on file extension.");
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            LOGGER.debug("Falling back to image/webp based on file extension.");
            return "image/webp";
        }

        throw new IllegalArgumentException("Unsupported image type. Use .jpg, .jpeg, .png, or .webp");
    }

    private static CliArgs parseArgs(String[] args) {
        // Extract expected command-line values from the --key=value arguments.
        String projectId = getArgValue(args, "projectId");
        String location = getArgValue(args, "location");
        String imagePath = getArgValue(args, "imagePath");
        String credentialsPath = getArgValue(args, "credentialsPath");

        // Fail fast when the minimum required arguments are missing.
        if (isBlank(projectId) || isBlank(imagePath) || isBlank(credentialsPath)) {
            throw new IllegalArgumentException("""
                    Missing required arguments.
                    Usage:
                    mvn spring-boot:run -Dspring-boot.run.arguments=\"--projectId=<PROJECT_ID> [--location=us-central1] --imagePath=<LOCAL_IMAGE_PATH> --credentialsPath=<SERVICE_ACCOUNT_JSON_PATH>\"
                    """);
        }

        // Apply the default location when the caller did not provide one explicitly.
        String resolvedLocation = isBlank(location) ? DEFAULT_LOCATION : location;
        LOGGER.debug("CLI arguments parsed successfully. projectId={}, location={}, imagePath={}, credentialsPath={}",
                projectId, resolvedLocation, imagePath, credentialsPath);
        return new CliArgs(projectId, resolvedLocation, imagePath, credentialsPath);
    }

    private static String getArgValue(String[] args, String name) {
        String prefix = "--" + name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record CliArgs(String projectId, String location, String imagePath, String credentialsPath) {
    }

    private static class ReceiptData {
        private String storeName;
        private String receiptNumber;
        private String date;
        private Double totalAmount;
        private List<LineItem> lineItems;
    }

    private static class LineItem {
        private String productName;
        private String productCode;
        private Integer quantity;
        private Double productPrice;
    }
}
