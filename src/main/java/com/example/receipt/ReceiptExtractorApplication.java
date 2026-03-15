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

    private static final String DEFAULT_LOCATION = "us-central1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        SpringApplication.run(ReceiptExtractorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        CliArgs cliArgs = parseArgs(args);

        Path credentialsPath = Path.of(cliArgs.credentialsPath());
        if (!Files.exists(credentialsPath)) {
            throw new IllegalArgumentException("Credentials file not found: " + credentialsPath);
        }

        try (var credentialsStream = Files.newInputStream(credentialsPath)) {
            GoogleCredentials.fromStream(credentialsStream);
        }

        System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", credentialsPath.toAbsolutePath().toString());

        Path imagePath = Path.of(cliArgs.imagePath());
        if (!Files.exists(imagePath)) {
            throw new IllegalArgumentException("Image file not found: " + imagePath);
        }

        byte[] imageBytes = Files.readAllBytes(imagePath);
        String mimeType = resolveMimeType(imagePath);

        Part imagePart = Part.newBuilder()
                .setInlineData(
                        Blob.newBuilder()
                                .setMimeType(mimeType)
                                .setData(ByteString.copyFrom(imageBytes))
                                .build())
                .build();

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

        Content content = Content.newBuilder()
                .setRole("user")
                .addParts(Part.newBuilder().setText(prompt).build())
                .addParts(imagePart)
                .build();

        try (VertexAI vertexAI = new VertexAI(cliArgs.projectId(), cliArgs.location())) {
            GenerativeModel model = new GenerativeModel("gemini-2.5-flash", vertexAI);
            GenerateContentResponse response = model.generateContent(content);

            String rawJson = ResponseHandler.getText(response).trim();
            ReceiptData receiptData = GSON.fromJson(rawJson, ReceiptData.class);

            printReceiptData(receiptData, rawJson);
            exportResultAsTextFile(receiptData, rawJson);
        } catch (ApiException ex) {
            if (ex.getStatusCode() != null && ex.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                ex.printStackTrace();
                System.err.println("Model not found or not accessible in this project/location.");
                System.err.println("Model   : gemini-2.5-flash");
                System.err.println("Project : " + cliArgs.projectId());
                System.err.println("Location: " + cliArgs.location());
                System.err.println("Try a model/region your project can access.");
                return;
            }
            throw ex;
        }
    }

    private static void printReceiptData(ReceiptData receiptData, String rawJson) {
        if (receiptData == null) {
            System.out.println("Model response was not valid JSON for ReceiptData.");
            System.out.println("Raw response:\n" + rawJson);
            return;
        }

        System.out.println("==== Extracted Receipt Data ====");
        System.out.println("Store Name    : " + receiptData.storeName);
        System.out.println("Receipt Number: " + receiptData.receiptNumber);
        System.out.println("Date          : " + receiptData.date);
        System.out.println("Total Amount  : " + receiptData.totalAmount);

        List<LineItem> items = receiptData.lineItems == null ? new ArrayList<>() : receiptData.lineItems;
        System.out.println("Line Items    : " + items.size());
        for (int i = 0; i < items.size(); i++) {
            LineItem item = items.get(i);
            System.out.println("  [" + (i + 1) + "]");
            System.out.println("    Product Name : " + item.productName);
            System.out.println("    Product Code : " + item.productCode);
            System.out.println("    Quantity     : " + item.quantity);
            System.out.println("    Product Price: " + item.productPrice);
        }

        System.out.println("\nParsed JSON (pretty):");
        System.out.println(GSON.toJson(receiptData));
    }

    private static void exportResultAsTextFile(ReceiptData receiptData, String rawJson) throws IOException {
        String storeName = receiptData != null && !isBlank(receiptData.storeName) ? receiptData.storeName : "UNKNOWN_STORE";
        String receiptNo = receiptData != null && !isBlank(receiptData.receiptNumber) ? receiptData.receiptNumber : "UNKNOWN_RECEIPT";
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        String fileName = sanitizeFileNamePart(storeName) + "+" + dateTime + "+" + sanitizeFileNamePart(receiptNo) + ".txt";
        Path outputPath = Path.of(fileName);

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

        Files.writeString(outputPath, content.toString());
        System.out.println("\nExported result file: " + outputPath.toAbsolutePath());
    }

    private static String sanitizeFileNamePart(String value) {
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "UNKNOWN" : cleaned;
    }

    private static String resolveMimeType(Path imagePath) throws IOException {
        String probed = Files.probeContentType(imagePath);
        if (probed != null && probed.startsWith("image/")) {
            return probed;
        }

        String lower = imagePath.getFileName().toString().toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }

        throw new IllegalArgumentException("Unsupported image type. Use .jpg, .jpeg, .png, or .webp");
    }

    private static CliArgs parseArgs(String[] args) {
        String projectId = getArgValue(args, "projectId");
        String location = getArgValue(args, "location");
        String imagePath = getArgValue(args, "imagePath");
        String credentialsPath = getArgValue(args, "credentialsPath");

        if (isBlank(projectId) || isBlank(imagePath) || isBlank(credentialsPath)) {
            throw new IllegalArgumentException("""
                    Missing required arguments.
                    Usage:
                    mvn spring-boot:run -Dspring-boot.run.arguments=\"--projectId=<PROJECT_ID> [--location=us-central1] --imagePath=<LOCAL_IMAGE_PATH> --credentialsPath=<SERVICE_ACCOUNT_JSON_PATH>\"
                    """);
        }

        String resolvedLocation = isBlank(location) ? DEFAULT_LOCATION : location;
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
