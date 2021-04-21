# DSPS-AWS1
First assignment in the course DSPS 2021B - Introduction to AWS in Java

## SQS Messages

### Local &rarr; Manager
* Attributes
	* `Name` : "New Task"
	* `Task` : unique `taskId` that will be used for the answer

* Body
```json
{
	"review-file-location" : "location_of_the_file_with_the_reviews_in_s3(.txt)",
	"task-id" : "unique task id used for the answer",
	"N" : "How many workers are requiered to process the file (Integer)",
	"terminate": "true or false"
}
```
> If the terminate field is `true` the manager will terminate all the workers once the task is done.

### Manager &rarr; Local
* Attributes
	* `Name` : "Task completed"
	* `Report` : unique `taskId` transmitted during task request

* Body
```json
{
	"report-file-location" : "location_of_the_file_with_the_report_in_s3(.txt)",
	"task-id" : "unique task id used for the answer (must be equal to the one sent during request",
	"terminated" : "true or false (Boolean)"
}
```
> If the terminated field is `true` that means all the workers have been terminated and the manager instance is ready to be terminated.

### Manager &rarr; Worker(s)
* Attributes
	* `Name` : "New Job"
	* `Job` : unique `jobId` __created by the manager__ to keep track of jobs (used for the response)

* Body
```json
{
	"title":"Where Is Baby's Belly Button? A Lift-the-Flap Book",
	"reviews":
	[
		{
			"id":"R14D3WP6J91DCU",
			"link":"https://www.amazon.com/gp/customer-reviews/R14D3WP6J91DCU/ref=cm_cr_arp_d_rvw_ttl?ie=UTF8&ASIN=0689835604",
			"title":"Five Stars","text":"Super cute book. My son loves lifting the flaps.",
			"rating":5,
			"author":"Nikki J",
			"date":"2017-05-01T21:00:00.000Z"
		},
		{
			"more reviews" : "..."
		}
	]
}
```
> The body is a file with a few reviews of an amazon product (`title`) in the JSON format to be processed by a worker

### Worker &rarr; Manager
* Attributes
	* `Name` : "Job Completed"
	* `Done` : unique `jobId` __transmitted by the manager__

* Body
```json
{
	"job-report-location" : "location_of_the_file_with_the_report_in_s3(.txt)",
	"job-id" : "jobID for the manager to recognize the request"
}
```

## Ec2 parameters
In order to know which size the workers instances had to be, we started testing the system with smaller requirements such as T2_SMALL or T2_MEDIUM. However, for both we ran into `Out of Memory` exceptions from the workers when the review they had to process was too large

#### WGET command on EC2
The wget utility is an HTTP and FTP client that allows you to download public objects from Amazon S3. It is installed by default in Amazon Linux.
To download an Amazon S3 object, use the following command, substituting the URL of the object to download:
```bash 
wget https://my_bucket.s3.amazonaws.com/path-to-file
```  
This method requires that the object you request is __public__; if the object is not public, you receive an __`ERROR 403: Forbidden`__ message. If you receive this error, open the Amazon S3 console and change the permissions of the object to public.  
[For more information](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AmazonS3.html)

## Security

[A Safer Way to Distribute AWS Credentials to EC2](https://aws.amazon.com/fr/blogs/security/a-safer-way-to-distribute-aws-credentials-to-ec2/)

## Todo list
- [x] Remove the files locally and on s3 once we are done with them
- [ ] ~Create 4 queues (one on each side, c.f [SQS Message](#sqs-messages))~
- [x] Run everything on AWS and wait for a result (try with a few files first!)
- [x] Don't forget to modify the jar files on s3
- [ ] ~Separate the manager private classes into different files~
- [x] Check why that many workers are created for a 50 lines input
- [ ] Run two localApplications to see if the manager still handler it
- [ ] Check for scalability 
