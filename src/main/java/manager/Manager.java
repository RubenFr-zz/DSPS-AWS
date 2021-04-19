package manager;

import awsService.*;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.*;
import java.util.*;

public class Manager {

    private static String MANAGER_ID;

    private static LinkedList <AMIService> WORKERS_HOLDER;
    private static Map<String, PrintWriter> REPORTS_HOLDER;
    private static Map<String, String> TASK_HOLDER;
    private static Map<String, Integer> JOBS_HOLDER;
    private static int tasks_id;

    private static StorageService s3;
    private static SimpleQueueService sqs_manager;
    private static SimpleQueueService sqs_workers;

    private static class TaskHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                // Fetch the next pending task (Review)
                Message task = sqs_manager.nextMessage(new String[]{"Task"});
                try {
                    handleNewTask(task);
                    sqs_manager.deleteMessage(task);
                } catch (ParseException | IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }

        private void handleNewTask(Message task) throws ParseException, IOException {

            ExtractContent content = new ExtractContent(task.body());

            // Download the review file from S3
            int task_id = tasks_id++;
            s3.downloadFile(content.review_file_location, "input-" + task_id);

            // Create workers if necessary
            for (int i = 0; i < content.N - WORKERS_HOLDER.size(); i++)
                WORKERS_HOLDER.add(new AMIService("worker-" + System.currentTimeMillis(), "worker"));

            // Create Report file
            REPORTS_HOLDER.put("Task-" + task_id, new PrintWriter(new FileWriter("report-" + task_id)));

            // Save task name for the response
            TASK_HOLDER.put("Task-" + task_id, task.messageAttributes().get("Task").stringValue());

            // Send to sqs all the queries
            BufferedReader reader = new BufferedReader(new FileReader("input-" + task_id));
            String line = reader.readLine();
            int numberOfLines = 0;
            while (line != null) {
                sendNewJob(task_id, line);
                line = reader.readLine();
                numberOfLines++;
            }

            // Save the number of jobs remaining to complete the task
            JOBS_HOLDER.put("Task-" + task_id, numberOfLines);

            // Remove Review file from s3
//            s3.removeFile(report_location);
        }

        private void sendNewJob(int task_id, String line) {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue taskNameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("New Job")
                    .build();
            MessageAttributeValue jobAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Task-" + task_id)
                    .build();
            messageAttributes.put("Name", taskNameAttribute);
            messageAttributes.put("Job", jobAttribute);

            sqs_workers.sendMessage(SendMessageRequest.builder()
                    .messageBody(line)
                    .messageAttributes(messageAttributes)
            );
        }

        private static class ExtractContent {
            protected String review_file_location;
            protected String task_id;
            protected int N;
            protected boolean terminate;

            protected ExtractContent(String jsonString) throws ParseException {
                JSONParser parser = new JSONParser();
                Object o = parser.parse(jsonString);
                JSONObject obj = (JSONObject) o;

                review_file_location = (String) obj.get("review-file-location");
                task_id = (String) obj.get("task-id");
                N = (int) obj.get("N");
                terminate = (boolean) obj.get("terminate");
            }
        }
    }

    private static class JobHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                // Fetch the next pending task (Review)
                Message job = sqs_workers.nextMessage(new String[]{"Done"});
                try {
                    handleJobEnding(job);
                    sqs_workers.deleteMessage(job);
                } catch (ParseException | IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }

        private void handleJobEnding(Message report) throws ParseException, IOException {

            ExtractContent content = new ExtractContent(report.body());

            // Download job report from s3
            String jobReportLocation = "report-" + System.currentTimeMillis();
            s3.downloadFile(content.job_report_location, jobReportLocation);

            // Add the job report to the task report
            BufferedReader reader = new BufferedReader(new FileReader(jobReportLocation));
            String line = reader.readLine();
            while(line != null) {
                REPORTS_HOLDER.get(content.task_id).println(line);
                line = reader.readLine();
            }

            // Delete the job report to save memory
            FileUtils.deleteQuietly(new File(jobReportLocation));

            // Decrease the number of jobs to complete
            JOBS_HOLDER.put(content.task_id, JOBS_HOLDER.get(content.task_id) - 1);

            // If there is no job left to complete send the final report to the local
            if (JOBS_HOLDER.get(content.task_id) == 0) completeTask(content.task_id);
        }

        // TODO: remove the task from the maps and send the message via sqs to the appropriate local
        private void completeTask(String task_id) {
        }


        private static class ExtractContent {
            protected String job_report_location;
            protected String task_id;

            protected ExtractContent(String jsonString) throws ParseException {
                JSONParser parser = new JSONParser();
                Object o = parser.parse(jsonString);
                JSONObject obj = (JSONObject) o;

                job_report_location = (String) obj.get("job-report-location");
                task_id = (String) obj.get("job-id");
            }
        }
    }

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        // Initialize
        MANAGER_ID = Long.toString(System.currentTimeMillis());
        WORKERS_HOLDER = new LinkedList<>();
        REPORTS_HOLDER = new HashMap<>();
        TASK_HOLDER = new HashMap<>();
        JOBS_HOLDER = new HashMap<>();
        sqs_workers = new SimpleQueueService("manager-queue-" + MANAGER_ID);
        tasks_id = 1;

        // Get the names of the AWS instances
        BufferedReader services = new BufferedReader(new FileReader("services"));
        String line = services.readLine();

        JSONParser parser = new JSONParser();
        Object o = parser.parse(line);
        JSONObject jsonObj = (JSONObject) o;

        // Initialize the AWS instances
        s3 = new StorageService((String) jsonObj.get("s3"));
        sqs_manager = new SimpleQueueService((String) jsonObj.get("sqs"));

        Thread taskHandlerThread = new Thread(new TaskHandler());
        Thread jobHandlerThread = new Thread(new JobHandler());

        taskHandlerThread.start();
        jobHandlerThread.start();

        taskHandlerThread.join();
        jobHandlerThread.join();
    }

    public static String getUserData(String id) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("#! /bin/bash" + '\n');
        lines.add("wget https://bucket-" + id + ".s3.amazonaws.com/Manager.jar" + '\n');
        lines.add("java -jar manager.jar " + '\n');
        return Base64.getEncoder().encodeToString(lines.toString().getBytes());
    }
}
