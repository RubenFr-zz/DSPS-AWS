# DSPS-AWS1
First assignement in the course DSPS 2021B - Introduction to AWS in Java

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

## Security

[A Safer Way to Distribute AWS Credentials to EC2](https://aws.amazon.com/fr/blogs/security/a-safer-way-to-distribute-aws-credentials-to-ec2/)

## Todo list
- [ ] Remove the files locally and on s3 once we are done with them
- [ ] Create 4 queues (one on each side, c.f SQS Message)
- [ ] Run everything on AWS and wait for a result (try with a few files first!)
- [ ] Don't forget to modify the jar files on s3
