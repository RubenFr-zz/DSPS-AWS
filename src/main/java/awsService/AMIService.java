package awsService;

import manager.Manager;
import worker.Worker;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

public class AMIService {

    //    private final String amiId = "ami-0e38cf7446e8264f3";
    private final String amiId = "ami-0009c3f63fca71e34"; // with java

    private final Ec2Client ec2;
    private final String instanceId;


    public AMIService(String id, String type) {
        ec2 = Ec2Client.builder().region(Region.US_EAST_1).build();
        switch (type) {
            case "manager":
                instanceId = startManager(id);
                break;
            case "worker":
                instanceId = startWorker(id);
                break;
            default:
                instanceId = "";
        }
    }

    public String startManager(String id) {
        // Check if the manager already exists
        String manager_id = findManager();

        if (manager_id != null) return manager_id;
        else return createInstance("manager-" + id, Manager.getUserData(id));
    }

    private String startWorker(String id) {
        return createInstance("worker-" + id, Worker.getUserData(id));
    }

    private String findManager() {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2.describeInstances(request);

        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                for (Tag tag : instance.tags())
                    if (tag.key().equals("Name"))
                        if (tag.value().contains("manager") && instance.state().name().name().equals("running"))
                            return instance.instanceId();
            }
        }
        return null;
    }

    public String createInstance(String name, String userData) {

        IamInstanceProfileSpecification role = IamInstanceProfileSpecification.builder()
                .name("EC2-role")
//                .name("EMR_EC2_DefaultRole")
                .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .instanceType(InstanceType.T2_MEDIUM)
                .imageId(amiId)
                .maxCount(1)
                .minCount(1)
                .userData(userData)
                .keyName("ec2-java-ssh")
                .securityGroupIds("sg-08106b9ce6c226627")
                .iamInstanceProfile(role)
                .build();


        RunInstancesResponse response = ec2.runInstances(runRequest);

        String instanceId = response.instances().get(0).instanceId();

        Tag tag = Tag.builder()
                .key("Name")
                .value(name)
                .build();

        CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(tag)
                .build();

        try {
            ec2.createTags(tagRequest);
            System.out.printf("Successfully started EC2 instance %s based on AMI %s\n", name, amiId);

        } catch (Ec2Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }

        return instanceId;
    }

    public void terminate() {
        TerminateInstancesRequest terminate_request = TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();
        ec2.terminateInstances(terminate_request);
        System.out.println("EC2 Instance terminated: " + instanceId);
    }
}