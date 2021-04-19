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

    private static LinkedList <AMIService> WORKERS_HOLDER;      // Holds the Ec2 instances of the workers running
    private static Map<String, PrintWriter> REPORTS_HOLDER;     // Holds the PrintWriter obj associated to each task report
    private static Map<String, String> TASK_HOLDER;             // Holds the id of the tasks sent by the local application
    private static Map<String, Integer> JOBS_HOLDER;            // Holds the number of jobs remaining for each task
    private static String terminate;                            // Holds the task's name that required termination
    private static int tasks_id;                                // Next task-id

    private static StorageService s3;                           // Connection to the s3
    private static SimpleQueueService sqs_local_app;            // Connection to the local-manager queue
    private static SimpleQueueService sqs_workers;              // Connection to the manager-workers queue

    private static class TaskHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                // Fetch the next pending task (Review)
                Message task = sqs_local_app.nextMessage(new String[]{"Task"});
                try {
                    handleNewTask(task);
                    sqs_local_app.deleteMessage(task);
                } catch (ParseException | IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }

        private void handleNewTask(Message task) throws ParseException, IOException {

            ExtractContent content = new ExtractContent(task.body());

            // Download the review file from S3
            String task_id = "Task-" + tasks_id++;
            s3.downloadFile(content.review_file_location, "input-" + task_id);

            // Create workers if necessary
            for (int i = 0; i < content.N - WORKERS_HOLDER.size(); i++)
                WORKERS_HOLDER.add(new AMIService("worker-" + System.currentTimeMillis(), "worker"));

            // Create Report file
            REPORTS_HOLDER.put(task_id, new PrintWriter(new FileWriter("report-" + task_id)));

            // Save task name for the response
            TASK_HOLDER.put(task_id, content.task_id);

            //Check if the task require termination at its completion
            if (content.terminate) terminate = task_id;

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
            JOBS_HOLDER.put(task_id, numberOfLines);

            // Remove Review file from s3
//            s3.removeFile(report_location);
        }

        private void sendNewJob(String task_id, String line) {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue taskNameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("New Job")
                    .build();
            MessageAttributeValue jobAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(task_id)
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

        private void completeTask(String task_id) {
            // Close the printer associated to the report file of this task and remove it from the map
            REPORTS_HOLDER.get(task_id).close();
            REPORTS_HOLDER.remove(task_id);

            // Remove the task from the JOB_HANDLER
            JOBS_HOLDER.remove(task_id);

            // Send a message to the corresponding local that its task is over
            if (terminate.equals(task_id)) terminateWorkers();
            sendCompletedTaskMessage(task_id);
            TASK_HOLDER.remove(task_id);
        }

        private void sendCompletedTaskMessage(String task_id) {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue taskNameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("New Report")
                    .build();
            MessageAttributeValue jobAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(TASK_HOLDER.get(task_id))
                    .build();
            messageAttributes.put("Name", taskNameAttribute);
            messageAttributes.put("Report", jobAttribute);

            JSONObject obj = new JSONObject();
            obj.put("report-file-location", "report-" + task_id);
            obj.put("task-id", TASK_HOLDER.get(task_id));
            obj.put("terminated", terminate.equals(task_id));

            sqs_local_app.sendMessage(SendMessageRequest.builder()
                    .messageBody(obj.toJSONString())
                    .messageAttributes(messageAttributes)
            );
        }

        private void terminateWorkers() {
            for (AMIService worker : WORKERS_HOLDER)
                worker.terminate();
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
        WORKERS_HOLDER = new LinkedList<>();
        REPORTS_HOLDER = new HashMap<>();
        TASK_HOLDER = new HashMap<>();
        JOBS_HOLDER = new HashMap<>();
        sqs_workers = new SimpleQueueService("manager-queue-" + System.currentTimeMillis());
        tasks_id = 1;

        // Get the names of the AWS instances
        BufferedReader services = new BufferedReader(new FileReader("services"));
        String line = services.readLine();

        JSONParser parser = new JSONParser();
        Object o = parser.parse(line);
        JSONObject jsonObj = (JSONObject) o;

        // Initialize the AWS instances
        s3 = new StorageService((String) jsonObj.get("s3"));
        sqs_local_app = new SimpleQueueService((String) jsonObj.get("sqs"));

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
