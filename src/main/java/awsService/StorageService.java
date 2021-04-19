package awsService;

import java.io.File;
import java.nio.file.Paths;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

public class StorageService {

    private final S3Client s3;
    private final String BUCKET_NAME;

    public StorageService(String id) {
        s3 = S3Client.builder()
//				.credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.US_EAST_1)
                .build();

        if (bucketExist(id)) {
            BUCKET_NAME = id;
            System.out.println("Connected to bucket: " + id);
        }else {
            BUCKET_NAME = "bucket-" + id;
            createBucket();
        }
    }

    private boolean bucketExist(String name) {
        ListBucketsResponse buckets = s3.listBuckets();
        for (Bucket bucket : buckets.buckets())
            if (bucket.name().equals(name)) return true;
        return false;
    }

    public void createBucket() {
        s3.createBucket(CreateBucketRequest
                .builder()
                .bucket(BUCKET_NAME)
                .createBucketConfiguration(
                        CreateBucketConfiguration.builder()
//						    .locationConstraint(region.id())
                                .build())
                .build());
        System.out.println("Bucket created: " + BUCKET_NAME);
    }

    public void deleteBucket() {
        // Before deleting a bucket we need to make sure it is empty
        emptyBucket();

        DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder().bucket(BUCKET_NAME).build();
        s3.deleteBucket(deleteBucketRequest);

        System.out.println("Bucket deleted: " + BUCKET_NAME);
    }

    private void emptyBucket() {
        // Get a list of all the files in the bucket
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(BUCKET_NAME)
//                .maxKeys(1)
                .build();

        ListObjectsV2Iterable listRes = s3.listObjectsV2Paginator(listReq);
        for (S3Object content : listRes.contents()) {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket(BUCKET_NAME).key(content.key()).build();
            s3.deleteObject(deleteObjectRequest);
            System.out.println("File deleted:\tKey: " + content.key() + "\tsize = " + content.size());
        }
    }

    public void uploadFile(String source, String destination) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(destination)
                        .build(),
                RequestBody.fromFile(new File(source)));
        System.out.println("File uploaded : " + destination);
    }

    public void downloadFile(String source, String destination) {
        s3.getObject(GetObjectRequest.builder().bucket(BUCKET_NAME).key(source).build(),
                ResponseTransformer.toFile(Paths.get(destination)));
        System.out.println("File downloaded: " + source);
    }

    public String getBucketName() {
        return this.BUCKET_NAME;
    }
}
