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
        BufferedReader services_buffer = new BufferedReader(new FileReader("services-worker"));
        String line = services_buffer.readLine();
        services_buffer.close();

        JSONParser parser = new JSONParser();
        Object o = parser.parse(line);
        JSONObject jsonObj = (JSONObject) o;

        // Initialize the AWS instances
        StorageService s3 = new StorageService((String) jsonObj.get("s3"));
        SimpleQueueService sqs_to_manager = new SimpleQueueService((String) jsonObj.get("sqs-to-manager"));
        SimpleQueueService sqs_from_manager = new SimpleQueueService((String) jsonObj.get("sqs-from-manager"));

        // Initialize the Review Analysis Handler
        ReviewAnalysisHandler handler = new ReviewAnalysisHandler();


        while (true) {
            try {
                // Fetch the next pending task (Review)
                Message job = sqs_from_manager.nextMessage();
                String job_name = job.messageAttributes().get("Name").stringValue();
                String task_name = job.messageAttributes().get("Job").stringValue();
                System.out.println("\nJob Received: " + job_name);

                // Run the review analysis
                LinkedList<String> result = handler.work(job.body());

                // Create the report file
                String fileName = "report-" + job_name;
                PrintWriter writer = new PrintWriter(new FileWriter(fileName));
                for (String report : result)
                    writer.println(report);
                writer.close();

                // Upload the report to s3 and delete locally
                s3.uploadFile(fileName, fileName);
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

                sqs_to_manager.sendMessage(SendMessageRequest.builder()
                        .messageBody(obj.toJSONString())
                        .messageAttributes(messageAttributes)
                );

                // Remove the executed task from the queue
                sqs_from_manager.deleteMessage(job);
                System.out.println("Job Completed: " + job_name);

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
