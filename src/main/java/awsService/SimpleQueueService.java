package awsService;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.*;

/**
 * Service client for accessing Amazon SQS.
 * Amazon Simple Queue Service (Amazon SQS) is a reliable, highly-scalable hosted queue for storing messages as they travel between applications or microservices.
 * <p>
 * Standard queues  are available in all regions.
 * You can use AWS SDKs  to access Amazon SQS using your favorite programming language. The SDKs perform tasks such as the following automatically:
 */
public class SimpleQueueService {

    private final SqsClient sqs;
    private final String QUEUE_NAME;
    private final String QUEUE_URL;

    /**
     * Builder for the {@link SimpleQueueService} class
     *
     * @param queue_name Name of the {@link SqsClient} we want to create or connect to
     */
    public SimpleQueueService(String queue_name) {
        String url;
        this.sqs = SqsClient.builder().region(Region.US_EAST_1).build();

        try {
            url = getQueueUrl(queue_name);
            System.out.println("Connected to queue: " + queue_name);

        } catch (QueueDoesNotExistException e) {
            createQueue(queue_name);
            url = getQueueUrl(queue_name);
            System.out.println("Queue created:\n\tid: " + queue_name + "\n\turl: " + url);
        }
        this.QUEUE_NAME = queue_name;
        this.QUEUE_URL = url;
    }

    /**
     * Creates a new standard queue {@link SqsClient}.
     *
     * @param queue_name The name of the new queue
     * @see CreateQueueRequest
     * @see CreateQueueResponse
     */
    private void createQueue(String queue_name) {
//        Map<QueueAttributeName, String> attributes = new HashMap<>();
//        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "1");

        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(queue_name)
//                .attributes(attributes)
                .build();

        sqs.createQueue(request);
    }

    /**
     * Returns the URL of an existing Amazon SQS queue.
     * If the specified queue doesn't exist it throws a {@code QueueDoesNotExistException}
     *
     * @param queue_name The name of the queue whose URL must be fetched.
     * @return The URL of the queue.
     * @throws QueueDoesNotExistException
     */
    private String getQueueUrl(String queue_name) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queue_name)
                .build();

        return sqs.getQueueUrl(getQueueRequest).queueUrl();
    }

    /**
     * Delivers a message to the specified queue.
     *
     * @param builder {@link SendMessageRequest.Builder} without destination queue
     * @see SendMessageRequest
     * @see SendMessageResponse
     */
    synchronized public void sendMessage(SendMessageRequest.Builder builder) {
        SendMessageRequest request = builder
                .queueUrl(QUEUE_URL)
                .build();

        sqs.sendMessage(request);
        System.out.println("Message sent: " + request.messageAttributes().get("Name").stringValue());
    }

    /**
     * Return at most {@code maxNumberOfMessages} message(s) pending in the specific queue containing the requested attribute
     *
     * @param attribute           The duration (in seconds) that the received messages are hidden from subsequent retrieve requests after being retrieved by a ReceiveMessage request.
     * @param visibilityTimeout   For how long hide the messages from other clients
     * @param maxNumberOfMessages The maximum number of messages to return. Amazon SQS never returns more messages than this value
     *                            (however, fewer messages might be returned). Valid values: 1 to 10.
     * @return A message pending in the specific queue containing the attribute
     */
    public List<Message> nextMessages(String attribute, int visibilityTimeout, int maxNumberOfMessages) {
        ReceiveMessageResponse response = receiveMessage(10, maxNumberOfMessages);
        List<Message> toReturn = new LinkedList<>();

        while (toReturn.isEmpty()) {
            for (Message message : response.messages()) {
                if (message.messageAttributes().containsKey(attribute)) {
                    // When we received a message that fits the requested attribute, we change th visibility time out to give us time to process the message (20 sec)
                    sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                            .queueUrl(QUEUE_URL)
                            .visibilityTimeout(visibilityTimeout)
                            .receiptHandle(message.receiptHandle())
                            .build());
                    System.out.println("Message Received: " + message.messageAttributes().get("Name").stringValue());
                    toReturn.add(message);
                }
            }
            response = receiveMessage(10, maxNumberOfMessages);
        }
        return toReturn;
    }

    /**
     * Return at most {@code maxNumberOfMessages} message(s) pending in the specific queue
     *
     * @param visibilityTimeout   The duration (in seconds) that the received messages are hidden from subsequent retrieve requests after being retrieved by a ReceiveMessage request.
     * @param maxNumberOfMessages The maximum number of messages to return. Amazon SQS never returns more messages than this value
     * @return A list of at most {@code maxNumberOfMessages} message(s) pending in the specific queue
     */
    public List<Message> nextMessages(int visibilityTimeout, int maxNumberOfMessages) {
        ReceiveMessageResponse response = receiveMessage(visibilityTimeout, maxNumberOfMessages);

        while (response.messages().isEmpty())
            response = receiveMessage(visibilityTimeout, maxNumberOfMessages);
        return response.messages();
    }

    /**
     * Send a {@code ReceiveMessageRequest} to the SQS a return the response
     *
     * @param visibilityTimeout   The duration (in seconds) that the received messages are hidden from subsequent retrieve requests after being retrieved by a ReceiveMessage request.
     * @param maxNumberOfMessages The maximum number of messages to return. Amazon SQS never returns more messages than this value
     * @return Returns the {@code ReceiveMessageResponse} of the request.
     * @see ReceiveMessageRequest
     * @see ReceiveMessageResponse
     */
    private ReceiveMessageResponse receiveMessage(int visibilityTimeout, int maxNumberOfMessages) {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest
                .builder()
                .queueUrl(QUEUE_URL)                        // Queue url
                .messageAttributeNames("All")               // What attributes to return with the message
                .maxNumberOfMessages(maxNumberOfMessages)   // How many messages we want to return with the request (in our case 1)
                .waitTimeSeconds(10)                        // The duration (in seconds) for which the call waits for a message to arrive in the queue before returning
                .visibilityTimeout(visibilityTimeout)       // The duration (in seconds) for which the received message will be hidden from other clients
                .build();

        return sqs.receiveMessage(receiveRequest);
    }

    /**
     * Deletes the specified message from the specified queue. To select the message to delete, use the ReceiptHandle of the message (not the MessageId which you receive when you send the message).
     * Amazon SQS can delete a message from a queue even if a visibility timeout setting causes the message to be locked by another consumer.
     * Amazon SQS automatically deletes messages left in a queue longer than the retention period configured for the queue.
     *
     * @param message Message to delete from the specific queue
     * @see DeleteMessageRequest
     * @see DeleteMessageResponse
     */
    public void deleteMessage(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build();

        sqs.deleteMessage(deleteRequest);
    }

    /**
     * Deletes the queue specified by the QueueUrl, regardless of the queue's contents. If the specified queue doesn't exist, Amazon SQS returns a successful response.
     */
    public void deleteQueue() {
        DeleteQueueRequest deleteRequest = DeleteQueueRequest
                .builder()
                .queueUrl(QUEUE_URL)    // Queue url
                .build();

        sqs.deleteQueue(deleteRequest);
        System.out.println("\nQueue Deleted: " + QUEUE_NAME);
    }

    /**
     * @return The name of the queue linked to this {@link SimpleQueueService}
     */
    public String getQueueName() {
        return this.QUEUE_NAME;
    }
}
