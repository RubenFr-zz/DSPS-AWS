# DSPS-AWS1
First assignment in the course DSPS 2021B - Introduction to AWS in Java

## Ec2 parameters
### Options
|      Type      |  vCPUs | Memory (GiB) |
| :------------: | :----: | :----------: | 
|__T2_MICRO__    | 1      | 1            |
|__T2_SMALL__    | 1      | 2            |
|__T2_MEDIUM__   | 2      | 4            |
|__T2_LARGE__    | 2      | 8            |
|__T2_XLARGE__   | 4      | 16           |

In order to know which type to chose we tested several combinations:
#### Manager
The Manager node in its conception isn't supposed to work a lot. It serves as a connection between the Local Application
and the Workers. In our design, the Manager runs two threads:
* Thread 1: Deals with the tasks request from the Local Application and the creation of new workers
* Thread 2: Creates the reports for each task by collecting the responses from the workers.

At our scale a `T2_MICRO` instance would have been enough. However, in order to make our system scalable, we
had to increase the Memory and decided to go for a `T2_MEDIUM` EC2 instance.

#### Worker
A Worker node, is by definition conceived to work hard and process as many jobs as possible. In order to complete a job, the
worker needs to use the `StanfordCoreNLP` dependency which requires a rather large amount of memory. Because we couldn't know
for sur what "a rather large amount of memory" meant, we had to perform a few tests to see which type could fit the best to
our design.  
We started testing the system with smaller requirements such as T2_SMALL or T2_MEDIUM. However, for both we ran into
`Out of Memory` exceptions from the workers when the review they had to process was too large. Finaly with a `T2_LARGE`
instance, the workers were able to easily process every kind of reviews.

#### WGET command on EC2
The wget utility is an HTTP and FTP client that allows you to download public objects from Amazon S3. It is installed by default in Amazon Linux.
To download an Amazon S3 object, use the following command, substituting the URL of the object to download:
```bash 
wget https://my_bucket.s3.amazonaws.com/path-to-file
```  
This method requires that the object you request is __public__; if the object is not public, you receive an __`ERROR 403: Forbidden`__
message. If you receive this error, open the Amazon S3 console and change the permissions of the object to public.  
[For more information](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AmazonS3.html)

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

The security is an important
[A Safer Way to Distribute AWS Credentials to EC2](https://aws.amazon.com/fr/blogs/security/a-safer-way-to-distribute-aws-credentials-to-ec2/)

## Scalability

#### Jobs partition
The input files are build so that every line is a JSONObject that contains several reviews associated to a specific product.
From what we have noticed there are usually 10 reviews per JSONObject.  
However, if somehow one of the JSONObject contains 1000 reviews and another only 5, we don't want for a worker to receive
such an amount of jobs. For that reason, the manager unfold all the reviews in the input file and sends to the workers several
job requests with 10 reviews to process (except maybe for the last request that can contain less). That way we know for sure
every worker will have the same jobs to process.

#### Workers
As we have seen [above](#jobs-partition), the jobs consist in a collection of 10 reviews. In order to maximize the task completion,
we decided to attribute to every worker 20 jobs (200 reviews/worker). That is, if a task contains 500 reviews to process,
3 workers will be created.

## Issues encountered

* Due to our student account, we are limited to 19 instances (1 manager and 18 workers). To prevent our account to be blocked,
  we limited the amount of workers to 15.  Hence, if somehow a task requires processing more than 3000 reviews, we won't be able
  to create more workers, and the amount of jobs per worker would grow (as well as the task's completion time).


* Let for a second suppose that the number of EC2 instances that we can crete isn't an issue (we can create as many workers
  as we want); in this case, if a lot of tasks (millions) are sent to the manager (by millions different local applications), the
  manager will be overwhelmed very fast as well as the sqs responsible to distribute job requests to the workers.

	* One of the solution would be to add more queues &rarr; more thread on the manager side (one for each queue) &rarr; concurrency issues ?
	* Maybe there is a need for more managers?


* Another issue we found in the assignment is the jobs' repartition:
  ```txt
  "If there are k active workers, and the new job requires m workers, then the manager should create m-k new workers, if possible"
  ```  
  The problem is that if millions of tasks only require 3 workers (<600 jobs per task), these 3 workers will have to deal with 
  the millions of job alone. For this reason, we keep a count of the `pending job` and when a new task is received
  by the manager, it will take into account the number of pending jobs in order to create more workers if necessary.  
  For instance, there are currently `3 workers` running, `450 jobs pending` and `N = 200`. A new task arrives with `500 new jobs`. 
  In total, there are now `950 jobs pending` for only 3 workers. Hence, `ceil(950/200) - 3 = _2_` more workers will be created.

## Todo list
- [x] Remove the files locally and on s3 once we are done with them
- [ ] ~Create 4 queues (one on each side, c.f [SQS Message](#sqs-messages))~
- [x] Run everything on AWS and wait for a result (try with a few files first!)
- [x] Don't forget to modify the jar files on s3
- [ ] ~Separate the manager private classes into different files~
- [x] Check why that many workers are created for a 50 lines input
- [x] Run two localApplications to see if the manager still handler it
- [x] Check for scalability 
