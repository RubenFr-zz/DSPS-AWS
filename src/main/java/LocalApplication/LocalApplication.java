package LocalApplication;

import awsService.*;

import org.apache.commons.io.FileUtils;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import worker.ReviewAnalysisHandler;

import org.javatuples.Quartet;

import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.json.simple.JSONObject;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;

import static j2html.TagCreator.*;

public class LocalApplication {

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        String inputFileName = args[0];
        String outputFileName = args[1];
        int N = Integer.parseInt(args[2]);
        boolean terminate = args.length == 4;

        run(inputFileName, outputFileName, N, terminate);

//        test();

        System.out.println("\ndone");
    }

    private static void test() throws IOException, ParseException {
//        StorageService s3 = new StorageService("bucket-dsps");
//        s3.uploadFile("Input files/0689835604.txt","reviews");

//        BufferedReader reader = new BufferedReader(new FileReader("Input files/0689835604.txt"));
//        String line = reader.readLine();
//
        SimpleQueueService sqs = new SimpleQueueService("queue-dsps");
//
//        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
//        MessageAttributeValue nameAttribute = MessageAttributeValue.builder()
//                .dataType("String")
//                .stringValue("New Task")
//                .build();
//        MessageAttributeValue taskAttribute = MessageAttributeValue.builder()
//                .dataType("String")
//                .stringValue("Task-" + System.currentTimeMillis())
//                .build();
//        messageAttributes.put("Name", nameAttribute);
//        messageAttributes.put("Task", taskAttribute);
//
//        sqs.sendMessage(SendMessageRequest.builder()
//                .messageBody(line)
//                .messageAttributes(messageAttributes)
//        );

        Message response = sqs.nextMessage(new String[]{"Report"});
        String report_location = response.messageAttributes().get("Report").stringValue();
        System.out.println("Report Location: " + report_location);
//        sqs.deleteMessage(response);

        // 9. Create html report
        writeReport(report_location, "report.html");
    }

    private static void run(String inputFileName, String outputFileName, int N, boolean terminate) throws IOException, ParseException {
        String id = Long.toString(System.currentTimeMillis());
        StorageService s3 = new StorageService(id);
        SimpleQueueService sqs = new SimpleQueueService(id);

        // 1. Upload Manager code to S3
        s3.uploadFile("target/Manager/Task1-dsps.jar", "Manager.jar");

        // 2. Upload Worker code to S3
        s3.uploadFile("target/Worker/Task1-dsps.jar", "Worker.jar");

        // 3. Upload Input File for Manager
        s3.uploadFile(inputFileName, "input-" + id);

        // 4. Upload services location to s3
        FileUtils.deleteQuietly(new File("services"));

        JSONObject obj = new JSONObject();
        obj.put("s3", s3.getBucketName());
        obj.put("sqs", sqs.getQueueName());

        BufferedWriter writer = new BufferedWriter(new FileWriter("services"));
        writer.write(obj.toJSONString());
        writer.close();

        s3.uploadFile("services", "services");

        // 5. Start Manager
        AMIService manager = new AMIService(id, "manager");

        // 6. Send Review Analysis request to the manager with the file locations
        sendAnalysisRequest(N, terminate, id, sqs);

        // 7. Wait for the manager to finish
        Message response = sqs.nextMessage(new String[]{"Report"});
        while (!response.messageAttributes().get("Report").stringValue().equals("task-" + id))
            response = sqs.nextMessage(new String[]{"Report"});

        ExtractResponse responseElements = new ExtractResponse(response.body());
        sqs.deleteMessage(response);

        // 8. Download report from S3
        s3.downloadFile(responseElements.report_location, "report-JSON-" + id);

        // 9. Create html report
        writeReport("report-JSON-" + id, outputFileName);

        // 10. Check if there is a need to terminate
        if (terminate && responseElements.terminated) {
            s3.deleteBucket();
            sqs.deleteQueue();
            manager.terminate();
        }
    }

    private static void sendAnalysisRequest(int N, boolean terminate, String id, SimpleQueueService sqs) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        MessageAttributeValue nameAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("New Task")
                .build();
        MessageAttributeValue taskAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("task-" + id)
                .build();
        messageAttributes.put("Name", nameAttribute);
        messageAttributes.put("Task", taskAttribute);

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

    private static void testSQS() {
        // Create new Queue
        String id = Long.toString(System.currentTimeMillis());
        SimpleQueueService sqs = new SimpleQueueService(id);

        for (int i = 0; i < 20; i++) sqs.sendMessage("Hello, World " + i + "!");

        Message message = sqs.nextMessage(new String[]{});
        System.out.println("1 message received: " + message.messageId());
        System.out.println(message.body());
        sqs.deleteMessage(message);

        sqs.deleteQueue();
    }

    private void testS3() {
        FileUtils.deleteQuietly(new File("received-file.txt"));

        String id = Long.toString(System.currentTimeMillis());
        StorageService s3 = new StorageService(id);
        s3.uploadFile("Hello.txt", "test.txt");
        s3.downloadFile("test.txt", "received-file.txt");
        s3.deleteBucket();
    }

    private static void testEC2() {
        try {
            String id = String.valueOf(System.currentTimeMillis());
            AMIService ec2 = new AMIService(id, "manager");
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    private static void testReport() throws IOException, ParseException {

        BufferedReader sourceReader = new BufferedReader(new FileReader("Input files/B01LYRCIPG.txt"));
        String input = sourceReader.readLine();

        File file = new File("downloaded.txt");
        System.out.println((file.delete() ? "Formatting file: " : "Creating file: ") + Paths.get("downloaded.txt").getFileName());
        PrintWriter writer = new PrintWriter(new FileWriter(file));

        ReviewAnalysisHandler handler = new ReviewAnalysisHandler();

        while (input != null) {
            for (String report : handler.work(input))
                writer.println(report);
            input = sourceReader.readLine();
        }
        writer.close();

        writeReport("downloaded.txt", "report.html");
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
        FileUtils.deleteQuietly(new File(destination)); // Remove
        BufferedWriter writer = new BufferedWriter(new FileWriter(destination));
        writer.write(report);
        writer.close();
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
        protected boolean terminated;

        protected ExtractResponse(String jsonString) throws ParseException {
            JSONParser parser = new JSONParser();
            Object o = parser.parse(jsonString);
            JSONObject obj = (JSONObject) o;

            report_location = (String) obj.get("report-file-location");
            task_id = (String) obj.get("task-id");
            terminated = (boolean) obj.get("terminated");
        }
    }
}

