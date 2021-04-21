package worker;

import awsService.SimpleQueueService;
import awsService.StorageService;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Worker implements Runnable {
    private static StorageService s3;
    private static SimpleQueueService sqs_to_manager;
    private static SimpleQueueService sqs_from_manager;

    private static ReviewAnalysisHandler handler;

    public static void main(String[] args) throws IOException, ParseException {
        // Get the names of the AWS instances
        BufferedReader services_buffer = new BufferedReader(new FileReader("services-worker"));
        String line = services_buffer.readLine();
        services_buffer.close();

        JSONParser parser = new JSONParser();
        Object o = parser.parse(line);
        JSONObject jsonObj = (JSONObject) o;

        // Initialize the AWS instances
        s3 = new StorageService((String) jsonObj.get("s3"));
        sqs_to_manager = new SimpleQueueService((String) jsonObj.get("sqs-to-manager"));
        sqs_from_manager = new SimpleQueueService((String) jsonObj.get("sqs-from-manager"));

        // Initialize the Review Analysis Handler
        handler = new ReviewAnalysisHandler();

        // The while loop run jobs after jobs. If somehow a job fails, it prints the error on the console and try running another job.
        // If a job failed, that means it hasn't been deleted on the queue (last operation), hence another worker will be able to execute it (after 10 min...)
        // If the job failed because of a QueueDoesNotExistException, terminate the worker...
        while (true) {
            Thread t = new Thread(new Worker());
            t.start();
            try {
                t.join();
            } catch (InterruptedException e1) {
                System.err.println(e1.getMessage());
            } catch (QueueDoesNotExistException e2) {
                System.err.println("\nDisconnected from the queue!");
                return;
            } catch (Exception e3) {
                System.err.println("\n" + e3.getMessage());
            }
        }
    }

    @Override
    public void run() {

        // Fetch the next pending task (Review)
        Message job = sqs_from_manager.nextMessage(1800);    // 30 minutes
        String job_name = job.messageAttributes().get("Name").stringValue();
        String sender = job.messageAttributes().get("Sender").stringValue();
        System.out.printf("\nJob Received: %s\tFrom: %s\n", job_name, sender);

        String result;
        try {
            result = handler.work(job.body());
        } catch (ParseException e) {
            throw new RuntimeException("\nThe job " + job_name + " failed...\nError: " + e.getMessage());
        }

        // Send a message with the result
        assert result != null;
        sendResult(job_name, sender, result);

        // Remove the executed task from the queue
        sqs_from_manager.deleteMessage(job);
        System.out.println("Job Completed: " + job_name);
    }

    private void sendResult(String job_name, String sender, String result) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        MessageAttributeValue nameAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("Job completed:\t" + job_name + "\tFrom: " + sender)
                .build();
        MessageAttributeValue reportAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(sender)
                .build();
        MessageAttributeValue doneAttribute = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("Job Completed")
                .build();
        messageAttributes.put("Name", nameAttribute);
        messageAttributes.put("Sender", reportAttribute);
        messageAttributes.put("Type", doneAttribute);

        sqs_to_manager.sendMessage(SendMessageRequest.builder()
                .messageBody(result)
                .messageAttributes(messageAttributes)
        );
    }

    public static String getUserData(String bucket) {
        String cmd = "#! /bin/bash" + '\n' +
                "wget https://" + bucket + ".s3.amazonaws.com/services-worker" + '\n' +
                "wget https://" + bucket + ".s3.amazonaws.com/Worker.jar" + '\n' +
                "java -jar Worker.jar > logger" + '\n';
        return Base64.getEncoder().encodeToString(cmd.getBytes());
    }
}
