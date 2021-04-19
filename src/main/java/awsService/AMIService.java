package awsService;

import manager.Manager;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

public class AMIService {

    private final String amiId = "ami-0e38cf7446e8264f3";
    private final Ec2Client ec2;
    private final String instanceId;


    public AMIService(String id, String type) {
        ec2 = Ec2Client.builder().region(Region.US_EAST_1).build();
        switch (type) {
            case "manager": instanceId = startManager(id); break;
            case "worker": instanceId = startWorker(id); break;
            default: instanceId = "";
        }
    }

    // TODO: Change the createInstance args and see if the workers already exist
    private String startWorker(String id) {
        // Check if the instance already exists
        String EC2_NAME = "worker-" + id;
        return createInstance(EC2_NAME, Manager.getUserData(id));
    }

    // TODO: Change the createInstance args and see if the workers already exist
    public String startManager(String id) {
        // Check if the instance already exists
        String EC2_NAME = "manager-" + id;
        return createInstance(EC2_NAME, Manager.getUserData(id));
    }

    public String createInstance(String name, String userData) {

        IamInstanceProfileSpecification role = IamInstanceProfileSpecification.builder()
                .arn("arn:aws:iam::752625253392:role/EC2-role")
                .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .instanceType(InstanceType.T2_MICRO)
                .imageId(amiId)
                .maxCount(1)
                .minCount(1)
                .userData(userData)
                .keyName("ec2-java-ssh")
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
            System.out.printf("Successfully started EC2 instance %s based on AMI %s\n",instanceId, amiId);

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
    }
}
