package manager;

import awsService.*;

import org.apache.commons.io.FileUtils;

import org.javatuples.Pair;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.*;
import java.util.*;

public class Manager {

    private static LinkedList<AMIService> WORKERS_HOLDER;       // Holds the Ec2 instances of the workers running
    private static Map<String, PrintWriter> REPORTS_HOLDER;     // Holds the PrintWriter obj associated to each task report
    private static Map<String, String> TASK_HOLDER;             // Holds the id of the tasks sent by the local application
    private static Map<String, Integer> JOBS_HOLDER;            // Holds the number of jobs remaining for each task
    private static LinkedList<String> ACTIVE_TASK;              // List of the class currently running
    private static int jobs_pending;                            // Number of jobs pending for execution
    private static Pair<String, String> terminate;              // Holds the task's name that required termination
    private static int tasks_id;                                // Next task-id
    private static int job_id;                                  // Next job-id

    private static String s3_name;                      // Connection to the s3
    private static String sqs_to_local_name;            // Connection to the manager-local queue
    private static String sqs_from_local_name;          // Connection to the local-manager queue
    private static String sqs_to_workers_name;          // Connection to the manager-workers queue
    private static String sqs_from_workers_name;        // Connection to the workers-manager queue
    private static String services_filename;            // File location of the services for the workers

    private static boolean stop_accepting_new_tasks, stop_receiving_from_workers, stop_manager;

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        // Initialize
        WORKERS_HOLDER = new LinkedList<>();
        REPORTS_HOLDER = new HashMap<>();
        TASK_HOLDER = new HashMap<>();
        JOBS_HOLDER = new HashMap<>();
        ACTIVE_TASK = new LinkedList<>();

        sqs_to_workers_name = "queue-to-workers";
        sqs_from_workers_name = "queue-from-workers";

        terminate = new Pair<>("", "");

        jobs_pending = 0;
        tasks_id = 1;
        job_id = 1;
        stop_accepting_new_tasks = false;
        stop_receiving_from_workers = false;
        stop_manager = false;

        // Get the names of the AWS instances
        BufferedReader services_buffer = new BufferedReader(new FileReader("services-manager"));
        String line = services_buffer.readLine();
        services_buffer.close();

        JSONParser parser = new JSONParser();
        Object o = parser.parse(line);
        JSONObject jsonObj = (JSONObject) o;

        // Initialize the AWS instances

        s3_name = (String) jsonObj.get("s3");
        sqs_to_local_name = (String) jsonObj.get("sqs-to-local");
        sqs_from_local_name = (String) jsonObj.get("sqs-from-local");

        // Create services file for workers
        services_filename = "services-worker";

        JSONObject obj = new JSONObject();
        obj.put("s3", s3_name);
        obj.put("sqs-to-manager", sqs_from_workers_name);
        obj.put("sqs-from-manager", sqs_to_workers_name);

        FileUtils.deleteQuietly(new File(services_filename));
        BufferedWriter writer = new BufferedWriter(new FileWriter(services_filename));
        writer.write(obj.toJSONString());
        writer.close();

        TaskHandler taskHandler = new TaskHandler();
        JobHandler jobHandler = new JobHandler();

        Thread taskHandlerThread = new Thread(taskHandler);
        Thread jobHandlerThread = new Thread(jobHandler);

        taskHandlerThread.start();
        jobHandlerThread.start();

        taskHandlerThread.join();
        jobHandlerThread.join();
        System.out.println("\nGracefully Terminated!");
    }

    private static class TaskHandler implements Runnable {

        private final StorageService s3;
        private final SimpleQueueService sqs_to_local;
        private final SimpleQueueService sqs_from_local;
        private final SimpleQueueService sqs_to_workers;

        public TaskHandler() {
            s3 = new StorageService(s3_name);
            sqs_to_local = new SimpleQueueService(sqs_to_local_name);
            sqs_from_local = new SimpleQueueService(sqs_from_local_name);
            sqs_to_workers = new SimpleQueueService(sqs_to_workers_name);
            s3.uploadFile(services_filename, "services-worker");
        }

        @Override
        public void run() {
            while (!stop_manager) {
                // Fetch the next pending task
                Message message = sqs_from_local.nextMessages(120, 1).get(0); // 2 min
                try {
                    switch (message.messageAttributes().get("Type").stringValue()) {
                        case "Task":
                            if (!stop_accepting_new_tasks) handleNewTask(message);
                            break;
                        case "Receipt":
                            handleLocalTermination(message);
                            break;
                        default:
                            System.err.println("Wrong message form:" + message.body());
                    }
                    sqs_from_local.deleteMessage(message);
                } catch (Exception e) {
                    if (!stop_accepting_new_tasks) {
                        System.err.println("\nERROR: " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
                }
            }
            sqs_to_workers.deleteQueue();
        }

        private void handleLocalTermination(Message message) {
            System.out.println("\nReceipt received from: " + message.body());
            ACTIVE_TASK.remove(message.body());

            if (ACTIVE_TASK.isEmpty() && stop_accepting_new_tasks) {
                System.out.println("\nEvery Local Applications received their reports.. \nTermination requested from " + terminate.getValue1());
                System.out.println("\nSending Termination Message to " + terminate.getValue1());
                sendTerminatedMessage();
                stop_manager = true;

                System.out.println("\nGracefully Terminated!");
                s3.uploadFile("logger", "logger-manager.txt");
            }
        }

        private void handleNewTask(Message task) throws ParseException, IOException {

            ExtractContent content = new ExtractContent(task.body());

            System.out.println("\nNew Task Received: " + content.task_id);

            // Download the review file from S3
            String task_id = "Task-" + tasks_id++;
            s3.downloadFile(content.review_file_location, "input-" + task_id);

            // Create Report file
            REPORTS_HOLDER.put(task_id, new PrintWriter(new FileWriter("report-" + task_id)));

            // Save task name for the response
            TASK_HOLDER.put(task_id, content.task_id);
            ACTIVE_TASK.add(content.task_id);

            //Check if the task require termination at its completion
            if (content.terminate) terminate = new Pair<>(task_id, content.task_id);

            // Load all the new jobs received
            LinkedList<String> jobs = loadJobs("input-" + task_id);

            // Save the number of jobs remaining to complete the task
            int numberOfJobs = jobs.size();
            JOBS_HOLDER.put(task_id, numberOfJobs);
            updateJobPending(numberOfJobs);

            // Create new workers if necessary (limit a maximum of 15 workers to run at the same time)
            int number_of_workers_top_create = (int) Math.ceil((double) jobs_pending / (double) content.N) - WORKERS_HOLDER.size();
            System.out.printf("\nCurrently running: %d worker(s)\n%d jobs have been sent (total: %d)-> %d additional worker(s) are required\n",
                    WORKERS_HOLDER.size(), numberOfJobs, jobs_pending, number_of_workers_top_create);

            for (int i = 0; i < Math.min(number_of_workers_top_create, 10 - WORKERS_HOLDER.size()); i++) {
                System.out.println("\nCreating Worker " + (i + 1));
                AMIService new_worker = new AMIService(s3.getBucketName(), "worker");
                WORKERS_HOLDER.add(new_worker);
            }
            System.out.print("\n"); // Empty line

            // Send job requests to workers
            for (String job : jobs)
                sendNewJob(task_id, job);
        }

        /**
         * Read and part the reviews from the input file
         *
         * @param file Location of the input files
         * @return A list of all the reviews for the workers to process
         * @throws IOException    The {@code file} is missing
         * @throws ParseException The {@code file} isn't a JSON
         */
        private LinkedList<String> loadJobs(String file) throws IOException, ParseException {
            LinkedList<String> jobs = new LinkedList<>();

            JSONParser parser = new JSONParser();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            while (line != null) {
                JSONArray reviews = (JSONArray) ((JSONObject) parser.parse(line)).get("reviews");
                for (Object o : reviews) jobs.add(((JSONObject) o).toJSONString());
                line = reader.readLine();
            }
            reader.close();
            return jobs;
        }

        private void sendNewJob(String task_id, String line) {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue taskNameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Job-" + job_id++)
                    .build();
            MessageAttributeValue jobAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Job")
                    .build();
            MessageAttributeValue senderAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(task_id)
                    .build();
            messageAttributes.put("Name", taskNameAttribute);
            messageAttributes.put("Sender", senderAttribute);
            messageAttributes.put("Type", jobAttribute);


            sqs_to_workers.sendMessage(SendMessageRequest.builder()
                    .messageBody(line)
                    .messageAttributes(messageAttributes)
            );
        }

        private void sendTerminatedMessage() {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue taskNameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Terminated")
                    .build();
            MessageAttributeValue reportAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(terminate.getValue1())
                    .build();
            MessageAttributeValue terminateAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Terminate")
                    .build();
            messageAttributes.put("Name", taskNameAttribute);
            messageAttributes.put("Target", reportAttribute);
            messageAttributes.put("Terminate", terminateAttribute);

            sqs_to_local.sendMessage(SendMessageRequest.builder()
                    .messageBody("Terminate")
                    .messageAttributes(messageAttributes)
                    .delaySeconds(10)       // To give time to the manager to gracefully finish
            );
        }

        private static class ExtractContent {
            protected String review_file_location;
            protected String task_id;
            protected long N;
            protected boolean terminate;

            protected ExtractContent(String jsonString) throws ParseException {
                JSONParser parser = new JSONParser();
                Object o = parser.parse(jsonString);
                JSONObject obj = (JSONObject) o;

                review_file_location = (String) obj.get("review-file-location");
                task_id = (String) obj.get("task-id");
                N = (Long) obj.get("N");
                terminate = (boolean) obj.get("terminate");
            }
        }
    }

    private static class JobHandler implements Runnable {

        private final StorageService s3;
        private final SimpleQueueService sqs_to_local;
        private final SimpleQueueService sqs_from_workers;

        public JobHandler() {
            s3 = new StorageService(s3_name);
            sqs_to_local = new SimpleQueueService(sqs_to_local_name);
            sqs_from_workers = new SimpleQueueService(sqs_from_workers_name);
        }

        @Override
        public void run() {
            while (!stop_receiving_from_workers) {
                // Fetch the next pending task (Review)
                List<Message> jobs = sqs_from_workers.nextMessages(120, 10); // 2 min

                for (Message job : jobs) {
                    try {
                        handleJobEnding(job);
                        sqs_from_workers.deleteMessage(job);
                    } catch (Exception e) {
                        System.err.println("\nERROR:" + e.getMessage());
                        if (!stop_receiving_from_workers) {
                            stop_accepting_new_tasks = true;
                        }
                        break;
                    }
                }
            }
            sqs_from_workers.deleteQueue();
        }

        private void handleJobEnding(Message report) {

            String message_name = report.messageAttributes().get("Name").stringValue();
            String message_sender = report.messageAttributes().get("Sender").stringValue();
            System.out.println(message_name);

            REPORTS_HOLDER.get(message_sender).println(report.body());

            // Decrease the number of jobs to complete
            JOBS_HOLDER.put(message_sender, JOBS_HOLDER.get(message_sender) - 1);
            updateJobPending(-1);

            // If there is no job left to complete send the final report to the local
            if (JOBS_HOLDER.get(message_sender) == 0) completeTask(message_sender);
        }

        private void completeTask(String task_id) {
            System.out.printf("\nTask completed. Creating Report for %s ...\n", TASK_HOLDER.get(task_id));
            // Close the printer associated to the report file of this task and remove it from the map
            REPORTS_HOLDER.get(task_id).close();
            REPORTS_HOLDER.remove(task_id);

            // Delete the input file locally
            FileUtils.deleteQuietly(new File("input-" + task_id));

            // Upload result to s3
            s3.uploadFile("report-" + task_id, "report-" + TASK_HOLDER.get(task_id));
            FileUtils.deleteQuietly(new File("report-" + task_id));

            // Remove the task from the JOB_HANDLER
            JOBS_HOLDER.remove(task_id);

            // By setting the terminated to true, the thread that accepts new tasks will be interrupted
            if (terminate.getValue0().equals(task_id)) stop_accepting_new_tasks = true;

            sendCompletedTaskMessage(task_id);
            TASK_HOLDER.remove(task_id);

            if (stop_accepting_new_tasks && jobs_pending == 0) {
                terminateWorkers();
                stop_receiving_from_workers = true;
            }
        }

        private void sendCompletedTaskMessage(String task_id) {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            MessageAttributeValue taskNameAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Report available for: " + TASK_HOLDER.get(task_id))
                    .build();
            MessageAttributeValue targetAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(TASK_HOLDER.get(task_id))
                    .build();
            MessageAttributeValue typeAttribute = MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("Report")
                    .build();
            messageAttributes.put("Name", taskNameAttribute);
            messageAttributes.put("Target", targetAttribute);
            messageAttributes.put("Type", typeAttribute);

            JSONObject obj = new JSONObject();
            obj.put("report-file-location", "report-" + TASK_HOLDER.get(task_id));
            obj.put("task-id", TASK_HOLDER.get(task_id));

            sqs_to_local.sendMessage(SendMessageRequest.builder()
                    .messageBody(obj.toJSONString())
                    .messageAttributes(messageAttributes)
            );
        }

        private void terminateWorkers() {
            s3.deleteFile("services-worker");
            FileUtils.deleteQuietly(new File("services-worker"));

            for (AMIService worker : WORKERS_HOLDER) {
                System.out.println("\nTerminating worker: " + worker.getInstanceId());
                worker.terminate();
            }
        }
    }

    /**
     * Update the value of {@code jobs_pending}.
     * Not the method is synchronized because we don't want two threads to update at the same time the value
     *
     * @param update Number to add or subtract from {@code jobs_pending}
     */
    static synchronized private void updateJobPending(int update) {
        jobs_pending += update;
    }

    public static String getUserData(String bucketName) {
        String cmd = "#! /bin/bash" + '\n' +
                "wget https://" + bucketName + ".s3.amazonaws.com/services-manager" + '\n' +
                "wget https://" + bucketName + ".s3.amazonaws.com/Manager.jar" + '\n' +
                "java -jar Manager.jar > logger" + '\n';
        return Base64.getEncoder().encodeToString(cmd.getBytes());
    }
}