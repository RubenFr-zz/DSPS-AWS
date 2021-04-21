package awsService;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.File;
import java.nio.file.Paths;

public class StorageService {

    private final S3Client s3;
    private final String BUCKET_NAME;

    /**
     * Builder for the {@link StorageService} class
     *
     * @param name Unique ID of the bucket we intend to create or connect to
     * @see S3Client
     * @see software.amazon.awssdk.services.ec2.model.S3Storage
     */
    public StorageService(String name) {
        s3 = S3Client.builder()
                .region(Region.US_EAST_1)
                .build();

        BUCKET_NAME = name;
        if (!bucketExist(BUCKET_NAME)) createBucket();
    }

    /**
     * @param name Name ID of the bucket we try to find
     * @return <code>True</code> if the bucket exists; <code>False</code> otherwise
     * @see ListBucketsResponse
     */
    private boolean bucketExist(String name) {
        ListBucketsResponse buckets = s3.listBuckets();
        for (Bucket bucket : buckets.buckets())
            if (bucket.name().equals(name)) {
                System.out.println("Connected to bucket: " + BUCKET_NAME);
                return true;
            }
        return false;
    }

    /**
     * Create a new bucket with the {@code BUCKET_NAME} global variable
     */
    public void createBucket() {
        CreateBucketRequest request = CreateBucketRequest.builder()
                .bucket(BUCKET_NAME)    // Name of the Bucket we are creating
                .build();

        s3.createBucket(request);
        System.out.println("Bucket created: " + BUCKET_NAME);
    }

    /**
     * Upload a file from {@code source} to {@code destination} to the specific bucket
     *
     * @param source      Location of the file to upload locally
     * @param destination Location on S3 to upload the file
     * @see PutObjectRequest
     */
    public void uploadFile(String source, String destination) {
        // Check if file already exists in the bucket
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(BUCKET_NAME).key(destination).build());
        } catch (NoSuchKeyException e){
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(destination)
                    .acl(ObjectCannedACL.PUBLIC_READ)     // Make the file available for read/write
                    .build();

            s3.putObject(request, RequestBody.fromFile(new File(source)));
            System.out.println("File uploaded : " + destination);
        }
    }

    /**
     * Download a a file from {@code source} to {@code destination} from the specific bucket
     *
     * @param source      Location of the file to download on S3
     * @param destination Location to download the file
     * @see GetObjectRequest
     */
    public void downloadFile(String source, String destination) {
        GetObjectRequest request = GetObjectRequest
                .builder()
                .bucket(BUCKET_NAME)
                .key(source)
                .build();

        s3.getObject(request, ResponseTransformer.toFile(Paths.get(destination)));
        System.out.println("File downloaded: " + source);
    }

    /**
     * Delete a {@code file} from the specific bucket
     * Removes the null version (if there is one) of an object and inserts a delete marker, which becomes the latest version of the object.
     * If there isn't a null version, Amazon S3 does not remove any objects.
     *
     * @param file Location of the file to delete on S3
     * @see DeleteObjectRequest
     */
    public boolean deleteFile(String file) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(file)
                .build();

        s3.deleteObject(request);
//        System.out.println("File deleted: " + file);
        return true;
    }

    /**
     * Deletes the bucket. All objects (including all object versions and Delete Markers) in
     * the bucket must be deleted before the bucket itself can be deleted.
     *
     * @see DeleteBucketRequest
     */
    public void deleteBucket() {
        // Before deleting a bucket we need to make sure it is empty
        emptyBucket();

        DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest
                .builder()
                .bucket(BUCKET_NAME)
                .build();

        s3.deleteBucket(deleteBucketRequest);
        System.out.println("Bucket deleted: " + BUCKET_NAME);
    }

    /**
     *  Remove all the files from the specific bucket (up to 100)
     * @see ListObjectsV2Iterable
     */
    private void emptyBucket() {
        // Get a list of all the files in the bucket
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(BUCKET_NAME)
//                .maxKeys(1)
                .build();

        ListObjectsV2Iterable listRes = s3.listObjectsV2Paginator(listReq);
        for (S3Object content : listRes.contents()) {
            deleteFile(content.key());
            System.out.println("File deleted:\tKey: " + content.key() + "\tsize = " + content.size());
        }
    }

    /**
     * Return the name of the specific bucket
     *
     * @return The name of the specific bucket
     */
    public String getBucketName() {
        return this.BUCKET_NAME;
    }
}
