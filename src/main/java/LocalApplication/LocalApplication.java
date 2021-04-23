package LocalApplication;

import awsService.*;

import org.apache.commons.io.FileUtils;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import org.javatuples.Quartet;

import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.json.simple.JSONObject;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static j2html.TagCreator.*;

public class LocalApplication {

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        long start = System.currentTimeMillis();

        String inputFileName = args[0];
        String outputFileName = args[1];
        int N = Integer.parseInt(args[2]);
        boolean terminate = args.length == 4;

        runApplication(inputFileName, outputFileName, N, terminate);

        long end = System.currentTimeMillis();
        System.out.printf("\nThe task execution took %d min and %d seconds",
                TimeUnit.MILLISECONDS.toMinutes(end - start), TimeUnit.MILLISECONDS.toSeconds(end - start)%60);

        System.out.println("\ndone");
    }

    private static void runApplication(String inputFileName, String outputFileName, int N, boolean terminate) throws IOException, ParseException {
        String id = Long.toString(System.currentTimeMillis());
        StorageService s3 = new StorageService("bucket-dsps");
        SimpleQueueService sqs_to_manager = new SimpleQueueService("queue-to-manager");
        SimpleQueueService sqs_from_manager = new SimpleQueueService("queue-from-manager");

        // 1. Upload Manager code to S3
//        s3.uploadFile("target/Manager/Task1-dsps.jar", "Manager.jar");

        // 2. Upload Worker code to S3
//        s3.uploadFile("target/Worker/Task1-dsps.jar", "Worker.jar");

        // 3. Upload Input File for Manager
        s3.uploadFile("Input_Files/" + inputFileName + ".txt", "input-" + id);

        // 4. Upload services location to s3 for the manager
        FileUtils.deleteQuietly(new File("services-manager"));

        JSONObject obj = new JSONObject();
        obj.put("s3", s3.getBucketName());
        obj.put("sqs-to-local", sqs_from_manager.getQueueName());
        obj.put("sqs-from-local", sqs_to_manager.getQueueName());

        BufferedWriter writer = new BufferedWriter(new FileWriter("services-manager"));
        writer.write(obj.toJSONString());
        writer.close();

        s3.uploadFile("services-manager", "services-manager");

        // 5. Start Manager
        AMIService manager = new AMIService(s3.getBucketName(), "manager");
        System.out.println("\nManager Running...\n");

        // 6. Send Review Analysis request to the manager with the file locations
        sendAnalysisRequest(N, terminate, id, sqs_to_manager);
        System.out.println("\nRequest sent! Wait for completion...\n");

        // 7. Wait for the manager to finish
        Message m1 = getMessage(id, sqs_from_manager);

        ExtractResponse responseElements = new ExtractResponse(m1.body());
        sqs_from_manager.deleteMessage(m1);

        // 8. Download report from S3
        s3.downloadFile(responseElements.report_location, "report-JSON-" + id);

        // 9. Create html report
        System.out.println("\nCreating HTML report for: " + "report-JSON-" + id);
        writeReport("report-JSON-" + id, outputFileName);

        // 10. Delete input and output files from s3
        s3.deleteFile("input-" + id);
        s3.deleteFile(responseElements.report_location);
        FileUtils.deleteQuietly(new File("report-JSON-" + id));

        // 11. Check if there is a need to terminate
        if (terminate) {
            System.out.println("\nWaiting for Termination...");

            Message m2 = getMessage(id, sqs_from_manager);
            sqs_from_manager.deleteMessage(m2);

            s3.deleteFile("services-manager");
            FileUtils.deleteQuietly(new File("services-manager"));

            System.out.println("\nTerminating the AWS instances:");
//            s3.deleteBucket();
            sqs_from_manager.deleteQueue();
            sqs_to_manager.deleteQueue();
            manager.terminate();
        }

        // 12. Open report in browser
        File htmlFile = new File(outputFileName + ".html");
        Desktop.getDesktop().browse(htmlFile.toURI());
    }

    private static Message getMessage(String id, SimpleQueueService sqs_from_manager) {
        Message response = sqs_from_manager.nextMessages(10,1).get(0); // 10 sec
        while (!response.messageAttributes().containsKey("Target")
                || !response.messageAttributes().get("Target").stringValue().equals("task-" + id))
            response = sqs_from_manager.nextMessages(10,1).get(0); // 10 sec
        return response;
    }

    private static void sendAnalysisRequest(int N, boolean terminate, String id, SimpleQueueService sqs) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        MessageAttributeValue nameAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("New Task from Local-" + id)
                .build();
        MessageAttributeValue taskAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("task-" + id)
                .build();
        MessageAttributeValue typeAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("Task")
                .build();
        messageAttributes.put("Name", nameAttribute);
        messageAttributes.put("Task", taskAttribute);
        messageAttributes.put("Type", typeAttribute);

        JSONObject toSend = new JSONObject();
        toSend.put("review-file-location", "input-" + id);
        toSend.put("task-id", "task-" + id);
        toSend.put("N", N);
        toSend.put("terminate", terminate);

        sqs.sendMessage(SendMessageRequest.builder()
                .messageBody(toSend.toJSONString())
                .messageAttributes(messageAttributes)
        );
    }

    private static void writeReport(String source, String destination) throws IOException, ParseException {

        BufferedReader sourceReader = new BufferedReader(new FileReader(source));
        JSONParser parser = new JSONParser();
        Object jsonObj;
        String jsonLine = sourceReader.readLine();

        LinkedList<Quartet<String, Integer, Integer, String>> reviews = new LinkedList<>();
        while (jsonLine != null) {
            jsonObj = parser.parse(jsonLine);
            JSONObject jsonObject = (JSONObject) jsonObj;
            String link = (String) jsonObject.get("link");
            int sentiment = ((Long) jsonObject.get("sentiment")).intValue();
            int rating = ((Long) jsonObject.get("rating")).intValue();
            String entities = (String) jsonObject.get("entities");
            reviews.add(new Quartet<>(link, rating, sentiment, entities));
            jsonLine = sourceReader.readLine();
        }
        sourceReader.close();

        String report =
                html()
                        .attr("lang", "en")
                        .with(
                                head(
                                        title("Output reviews"),
                                        link().withRel("stylesheet").withHref("/css/main.css")
                                ),
                                style("table, th, td { border: 1px solid black;  text-align: center; vertical-align: middle; padding: 5px;}\n\t\t" +
                                        "table {border-spacing: 5px;}\n\t\t" +
                                        "th {font-size: 30px;}\n\t\t" +
                                        "td {font-size: 20px;}\n\t\t" +
                                        "tr:nth-child(even) {background-color: #f2f2f2;}"),
                                body(
                                        h1("Classification of the Amazon reviews"),
                                        br(),
                                        table(
                                                tr(
                                                        th("Link")
                                                                .withStyle("width: 45%;"),
                                                        th("Entities")
                                                                .withStyle("width: 35%;"),
                                                        th("Sarcasm Detection")
                                                                .withStyle("width: 20%;")
                                                ),
                                                each(reviews, review ->
                                                        tr(
                                                                td(
                                                                        a(review.getValue0().split("/ref")[0])
                                                                                .withStyle("color:" + getColor(review.getValue2()))
                                                                                .withTarget("_blank")
                                                                                .withHref(review.getValue0())
                                                                ),
                                                                td(review.getValue3()),
                                                                td(b(isSarcasm(review.getValue1(), review.getValue2())))
                                                        )
                                                )
                                        )
                                                .withStyle("width: 100%")
                                )
                        )
                        .renderFormatted();


        // Save the result in a HTML file.
        FileUtils.deleteQuietly(new File(destination));
        BufferedWriter writer = new BufferedWriter(new FileWriter(destination + ".html"));
        writer.write(report);
        writer.close();
        System.out.println("Report ready: " + destination + ".html");
    }

    private static String isSarcasm(int value1, int value2) {
        return value1 == value2 ? "No Sarcasm" : "Sarcasm";
    }

    private static String getColor(int sentiment) {
        switch (sentiment) {
            // Very Negative
            case 1:
                return ("#A11E02");      // Dark Red
            case 2:
                return ("#FF0000");      // Red
            case 3:
                return ("#000000");      // Black
            case 4:
                return ("#8FBC8F");      // Light Green
            case 5:
                return ("#006400");      // Dark Green
            // Very Positive
            default:
                return null;
        }
    }

    private static class ExtractResponse {
        protected String report_location;
        protected String task_id;

        protected ExtractResponse(String jsonString) throws ParseException {
            JSONParser parser = new JSONParser();
            Object o = parser.parse(jsonString);
            JSONObject obj = (JSONObject) o;

            report_location = (String) obj.get("report-file-location");
            task_id = (String) obj.get("task-id");
        }
    }
}

