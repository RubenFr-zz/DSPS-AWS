package worker;

import awsService.SimpleQueueService;
import awsService.StorageService;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.*;
import java.util.*;

public class Worker {

    public static void main(String[] args) throws IOException, ParseException {
        // Get the names of the AWS instances
        BufferedReader services = new BufferedReader(new FileReader("services-worker"));
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
            try {
                // Fetch the next pending task (Review)
                Message task = sqs.nextMessage("Job");
                String task_name = task.messageAttributes().get("Job").stringValue();

                // Run the review analysis
                LinkedList<String> reports = handler.work(task.body());

                // Create the report file
                String fileName = "report-" + System.currentTimeMillis();
                PrintWriter writer = new PrintWriter(new FileWriter(fileName));
                for (String report : reports)
                    writer.println(report);
                writer.close();

                // Upload the report to s3
                s3.uploadFile(fileName, fileName);

                // Delete the file locally to save memory
                FileUtils.deleteQuietly(new File(fileName));

                // Send a message that the computation is over with the location of the report on s3
                Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
                MessageAttributeValue nameAttribute = MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("New Report")
                        .build();
                MessageAttributeValue reportAttribute = MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(task_name)
                        .build();
                messageAttributes.put("Name", nameAttribute);
                messageAttributes.put("Done", reportAttribute);

                JSONObject obj = new JSONObject();
                obj.put("job-report-location", fileName);
                obj.put("job-id", task_name);

                sqs.sendMessage(SendMessageRequest.builder()
                        .messageBody(obj.toJSONString())
                        .messageAttributes(messageAttributes)
                );

                // Remove the executed task from the queue
                sqs.deleteMessage(task);
            } catch (Exception e) {
                System.out.println(e.getMessage());
                break;
            }
        }
    }

    public static String getUserData(String bucket) {
        String cmd = "#! /bin/bash" + '\n' +
                "wget https://" + bucket + ".s3.amazonaws.com/services-worker" + '\n' +
                "wget https://" + bucket + ".s3.amazonaws.com/Worker.jar" + '\n' +
                "java -jar Worker.jar > logger" + '\n';
        return Base64.getEncoder().encodeToString(cmd.getBytes());
    }
}
