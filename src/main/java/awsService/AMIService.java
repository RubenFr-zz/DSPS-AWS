package awsService;

import manager.Manager;
import worker.Worker;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

public class AMIService {

    private final Ec2Client ec2;
    private final String instanceId;

    /**
     * Builder for the {@link AMIService} class
     *
     * @param bucket Name of the {@link S3Storage} in which is stored the Worker.jar file for the instance to run
     * @param type   String ["manager", "worker"]
     */
    public AMIService(String bucket, String type) {
        ec2 = Ec2Client.builder()
                .region(Region.US_EAST_1)   // Region to create/load the instance
                .build();

        switch (type) {
            case "manager":
                instanceId = startManager(bucket);
                break;
            case "worker":
                instanceId = startWorker(bucket);
                break;
            default:
                instanceId = "";
        }
    }

    /**
     * @param bucket Name of the {@link S3Storage} in which is stored the Worker.jar file for the instance to run
     * @return Unique instance id of the created or connected {@link Ec2Client}
     */
    public String startManager(String bucket) {
        // Check if the manager already exists
        String manager_id = findManager();

        if (manager_id != null) {
            System.out.println("Connected to " + manager_id);
            return manager_id;
        } else return createInstance("manager-" + System.currentTimeMillis(), Manager.getUserData(bucket));
    }

    /**
     * @param bucket Name of the {@link S3Storage} in which is stored the Worker.jar file for the instance to run
     * @return Unique instance id of the created {@link Ec2Client}
     */
    private String startWorker(String bucket) {
        return createInstance("worker-" + System.currentTimeMillis(), Worker.getUserData(bucket));
    }

    /**
     * Look for a running manager EC2 instance.
     * If it finds it, return it's instance ID to access it
     *
     * @return If a running manager exists, it returns its instance ID
     * Else null
     * @see DescribeInstancesRequest
     * @see DescribeInstancesResponse
     */
    private String findManager() {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2.describeInstances(request);

        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                for (Tag tag : instance.tags())
                    if (tag.key().equals("Type"))
                        if (tag.value().equals("manager") && instance.state().name() == InstanceStateName.RUNNING)
                            return instance.instanceId();
            }
        }
        return null;
    }

    /**
     * Create a new single EC2 instance with two tags Name and Type.
     * The instance is an image of an existing AMI with Java 8 preinstalled.
     * The instance type can be T2_MEDIUM (manager) or T2_Large (worker)
     *
     * @param name     Name Tag of the instance
     * @param userData The user data to make available to the instance. Must be base64-encoded text.
     * @return Unique instance ID of the instance created
     * @see IamInstanceProfileSpecification
     * @see RunInstancesRequest
     * @see RunInstancesResponse
     */
    public String createInstance(String name, String userData) {
        // IAM role. Gives accreditations to the new instance
        IamInstanceProfileSpecification role = IamInstanceProfileSpecification.builder()
                .name("EC2-role")   // Contain policies for EC2, SQS and S3
                .build();

        // The workers need to run on T2_LARGE instances because of the NLP libraries that requires a lot of memory
        InstanceType type = name.contains("manager") ? InstanceType.T2_MEDIUM : InstanceType.T2_LARGE;

        // AMI with Java
        String amiId = "ami-0009c3f63fca71e34";

        RunInstancesRequest runRequest = RunInstancesRequest
                .builder()
                .instanceType(type)                         // Instance size (Medium or Large)
                .imageId(amiId)                             // AMI
                .maxCount(1)                                // Max number of instances to create (in our case 1)
                .minCount(1)                                // Min number of instances to create (in our case 1)
                .userData(userData)                         // User data -> bash code to run during initialization
                .keyName("ec2-java-ssh")                    // SSH to access to the instance when created
                .securityGroupIds("sg-08106b9ce6c226627")   // Security group (what ports and protocols are available)
                .iamInstanceProfile(role)                   // Policies
                .build();


        RunInstancesResponse response = ec2.runInstances(runRequest);
        String instanceId = response.instances().get(0).instanceId();   // unique ID of the created instance

        // Add a Name tag to recognize the created instance in the AWS console
        Tag nameTag = Tag.builder()
                .key("Name")
                .value(name)
                .build();

        // Add a Type tag (manager or worker)
        Tag typeTag = Tag.builder()
                .key("Type")
                .value(name.split("-")[0])
                .build();

        CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(nameTag, typeTag)
                .build();

        try {
            ec2.createTags(tagRequest);
            System.out.printf("Successfully started EC2 instance %s based on AMI %s\n", name, amiId);

        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }

        return instanceId;
    }

    /**
     * Terminate the {@link Ec2Client} linked to the {@link AMIService} Object
     *
     * @see TerminateInstancesRequest
     */
    public void terminate() {
        TerminateInstancesRequest terminate_request = TerminateInstancesRequest
                .builder()
                .instanceIds(instanceId)
                .build();

        ec2.terminateInstances(terminate_request);
        System.out.println("\nEC2 Instance terminated: " + instanceId);
    }

    /**
     * Return the name of the {@link Ec2Client}
     *
     * @return The name of the specific EC2 instance
     */
    public String getInstanceId() {
        return instanceId;
    }
}