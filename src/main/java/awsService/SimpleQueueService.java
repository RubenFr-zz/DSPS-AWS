package awsService;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.*;

public class SimpleQueueService {

    private final SqsClient sqs;
    private final String QUEUE_NAME;
    private final String QUEUE_URL;
    private final boolean FIFO;

    public SimpleQueueService(String id) {
        String url;
        sqs = SqsClient.builder().region(Region.US_EAST_1).build();

        try {
            url = getQueueUrl(id);
            System.out.println("Connected to queue: " + id);

        } catch (QueueDoesNotExistException e) {
            createQueue(id, id.contains("fifo"));
            url = getQueueUrl(id);
            System.out.println("Queue created:\n\tid: " + id + "\n\turl: " + url);
        }
        QUEUE_NAME = id;
        QUEUE_URL = url;
        FIFO = id.contains("fifo");
    }

    private void createQueue(String queue_name, boolean fifo) {
        Map<QueueAttributeName, String> attributes = new HashMap<>();
//        attributes.put(QueueAttributeName.FIFO_QUEUE, Boolean.toString(fifo));
        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "1");

        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(queue_name)
                .attributes(attributes)
                .build();

        sqs.createQueue(request);
    }

    private String getQueueUrl(String queue_name) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queue_name)
                .build();

        return sqs.getQueueUrl(getQueueRequest).queueUrl();
    }

    public void sendMessage(SendMessageRequest.Builder message) {
        SendMessageRequest.Builder messageBuilder = message
                .queueUrl(QUEUE_URL);
        if (FIFO) messageBuilder = messageBuilder.messageGroupId("message");

        SendMessageRequest request = messageBuilder.build();
        sqs.sendMessage(request);
        System.out.println("Message sent: " + request.messageAttributes().get("Name").stringValue());
    }

    public Message nextMessage(String attribute) {
        ReceiveMessageResponse response;

        while (true) {
            while ((response = receiveMessage()).messages().isEmpty()) ;
            Message message = response.messages().get(0);

            if (message.messageAttributes().containsKey(attribute)) {
                // When we received a message that fits the requested attribute, we change th visibility time out to give us time to process the message (20 sec)
                sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                        .queueUrl(QUEUE_URL)
                        .visibilityTimeout(20)
                        .receiptHandle(message.receiptHandle())
                        .build());
                System.out.println("Message Received: " + message.messageAttributes().get("Name").stringValue());
                return message;
            }
        }
    }

    /**
     * <p>
     * Send a ReceiveMessageRequest to the SQS a return the response
     * </p>
     *
     * @return Returns the ReceiveMessageResponse of the request.
     */
    private ReceiveMessageResponse receiveMessage() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest
                .builder()
                .queueUrl(QUEUE_URL)            // Queue url
                .messageAttributeNames("All")   // What attributes to return with the message
                .maxNumberOfMessages(1)         // How many messages we want to return with the request (in our case 1)
                .waitTimeSeconds(20)            // The duration (in seconds) for which the call waits for a message to arrive in the queue before returning
                .visibilityTimeout(1)           // The duration (in seconds) for which the received message will be hidden from other clients
                .build();

        return sqs.receiveMessage(receiveRequest);
    }

    public void deleteMessage(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build();
        sqs.deleteMessage(deleteRequest);
    }

    public void deleteQueue() {
        DeleteQueueRequest deleteRequest = DeleteQueueRequest.builder().queueUrl(QUEUE_URL).build();
        sqs.deleteQueue(deleteRequest);
        System.out.println("Queue Deleted: " + QUEUE_NAME);
    }

    public String getQueueName() {
        return this.QUEUE_NAME;
    }
}
