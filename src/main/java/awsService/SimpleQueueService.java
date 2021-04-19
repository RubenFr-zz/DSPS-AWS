package awsService;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.*;

public class SimpleQueueService {

    private final SqsClient sqs;
    private final String QUEUE_NAME;
    private final String QUEUE_URL;

    public SimpleQueueService(String id) {
        String url, name;
        sqs = SqsClient.builder().region(Region.US_EAST_1).build();

        try {
            url = getQueueUrl(id);
            name = id;
            System.out.println("Connected to queue: " + id);

        } catch (QueueDoesNotExistException e) {
            name = "Queue-" + id;
            createQueue(name);
            url = getQueueUrl(name);
            System.out.println("Queue created:\n\tid: " + name + "\n\turl: " + url);
        }

        QUEUE_NAME = name;
        QUEUE_URL = url;
    }

    private void createQueue(String queue_name) {
        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(queue_name)
                .build();
        sqs.createQueue(request);
    }

    private String getQueueUrl(String queue_name) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(queue_name)
                .build();

        return sqs.getQueueUrl(getQueueRequest).queueUrl();
    }

    public void sendMessage(String message) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>(1);
        attributes.put("Name", MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("message-" + System.currentTimeMillis())
                .build()
        );

        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .messageAttributes(attributes)
                .messageBody(message)
//                .delaySeconds(5)
                .build();

        sqs.sendMessage(send_msg_request);
        System.out.println("Message sent: " + send_msg_request.messageAttributes().get("Name").stringValue());
    }

    public void sendMessage(SendMessageRequest.Builder message) {
        SendMessageRequest request = message
                .queueUrl(QUEUE_URL)
                .delaySeconds(5)
                .build();
        sqs.sendMessage(request);
        System.out.println("Message sent: " + request.messageBody());
    }

    public void sendMessages(LinkedList<String> messages) {
        LinkedList<SendMessageBatchRequestEntry> entries = new LinkedList<>();
        Iterator<String> it = messages.iterator();
        int i = 1;

        while (it.hasNext()) {
            entries.add(SendMessageBatchRequestEntry.builder()
                    .messageBody(it.next())
                    .id("msg" + i++)
                    .build());
        }

        SendMessageBatchRequest send_batch_request = SendMessageBatchRequest.builder()
                .queueUrl(QUEUE_URL)
                .entries(entries)
                .build();

        sqs.sendMessageBatch(send_batch_request);
    }

    public Message nextMessage(String[] attributeNames) {
        List<Message> messages;

        while(true) {
            while ((messages = receiveMessage()).isEmpty()) ;
            for (String attribute : attributeNames)
                if (messages.get(0).messageAttributes().containsKey(attribute))
                    return messages.get(0);
        }

    }
    private List<Message> receiveMessage() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .messageAttributeNames("All")
                .maxNumberOfMessages(1)
                .waitTimeSeconds(10)
                .visibilityTimeout(10)
                .build();

        return sqs.receiveMessage(receiveRequest).messages();
    }

    public void deleteMessage(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build();
        sqs.deleteMessage(deleteRequest);
    }

    public void deleteMessages(List<Message> messages) {
        for (Message m : messages)
            deleteMessage(m);
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
