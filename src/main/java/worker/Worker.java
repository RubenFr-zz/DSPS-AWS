package worker;

import awsService.SimpleQueueService;
import awsService.StorageService;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class Worker {

    public static void main(String[] args) throws IOException, ParseException {
        // Get the names of the AWS instances
        BufferedReader services = new BufferedReader(new FileReader("services"));
        String line = services.readLine();

        JSONParser parser = new JSONParser();
        Object o = parser.parse(line);
        JSONObject jsonObj = (JSONObject) o;

        // Initialize the AWS instances
        StorageService s3 = new StorageService((String) jsonObj.get("s3"));
        SimpleQueueService sqs = new SimpleQueueService((String) jsonObj.get("sqs"));

        // Initialize the Review Analysis Handler
        ReviewAnalysisHandler handler = new ReviewAnalysisHandler();

        while (true) {
            // Fetch the next pending task (Review)
            Message task = sqs.nextMessage(new String[]{"Job"});
            String task_name = task.messageAttributes().get("Job").stringValue();

            // Run the review analysis
            LinkedList<String> reports = handler.work(task.body());

            // Create the report file
            String fileName = "report-" + System.currentTimeMillis();
            PrintWriter writer = new PrintWriter(new FileWriter(fileName, true));
            for (String report : reports)
                writer.println(report);
            writer.close();

            // Upload the report to s3
            s3.uploadFile(fileName, fileName);

            // Send a message that the computation is over with the location of the report on s3
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue nameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("report-" + System.currentTimeMillis())
                    .build();
            MessageAttributeValue reportAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(fileName)
                    .build();
            messageAttributes.put("Name", nameAttribute);
            messageAttributes.put("Done", reportAttribute);

            sqs.sendMessage(SendMessageRequest.builder()
                    .messageBody("Report for task: " + task_name + "\nFile location: " + fileName)
                    .messageAttributes(messageAttributes)
            );

             // Remove the executed task from the queue
            sqs.deleteMessage(task);
        }
    }
}
