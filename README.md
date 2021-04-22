# DSPS-AWS1

First assignment in the course DSPS 2021B - Introduction to AWS in Java

<p align="center">
  <a href="#dsps-aws1"><img src="https://miro.medium.com/max/4000/1*b_al7C5p26tbZG4sy-CWqw.png" width="350" title="AWS" target="_blank"/></a>
</p>

1. [EC2 paramters](#ec2-parameters)
2. [AWS instances used](#aws-instances-used)
3. [Scalability](#scalability)
4. [Security](#security)
5. [Issues encountered](#issues-encountered)
6. [Todo list](#todo-list)


## EC2 parameters

### Type

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

At our scale a `T2_MICRO` instance would have been enough. However, in order to make our system scalable, we had to
increase the Memory and decided to go for a `T2_MEDIUM` EC2 instance.

#### Worker

A Worker node, is by definition conceived to work hard and process as many jobs as possible. In order to complete a job,
the worker needs to use the `StanfordCoreNLP` dependency which requires a rather large amount of memory. Because we
couldn't know for sur what "a rather large amount of memory" meant, we had to perform a few tests to see which type
could fit the best to our design.  
We started testing the system with smaller requirements such as T2_SMALL or T2_MEDIUM. However, for both we ran into
`Out of Memory` exceptions from the workers when the review they had to process was too large. Finaly with a `T2_LARGE`
instance, the workers were able to easily process every kind of reviews.

### WGET command on EC2

The wget utility is an HTTP and FTP client that allows you to download public objects from Amazon S3. It is installed by
default in Amazon Linux. To download an Amazon S3 object, use the following command, substituting the URL of the object
to download:

```bash 
wget https://my_bucket.s3.amazonaws.com/path-to-file
```  

This method requires that the object you request is __public__; if the object is not public, you receive
an __`ERROR 403: Forbidden`__
message. If you receive this error, open the Amazon S3 console and change the permissions of the object to public.  
[For more information](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AmazonS3.html)

## AWS instances used
> "It is up to you to decide how many queues to use and how to split the jobs among the workers, but, and you will be graded accordingly,
> your system should strive to work in parallel. It should be as efficient as possible in terms of time and money, and scalable"

One of the hardest decisions in our design was the utilization of AWS instances. AWS is really cheap (although not free),
and the more instances/queues/storage/power/... you use, the fastest the jobs will be completed. The whole challenge was to
weight the tradeoffs and find the most appropriate implementation to complete the reviews' analysis efficiently.

#### Experimentation :

1. First, we played the cheap card and used:
  - 2 SQS (Local &hArr; Manager &hArr; Workers)
  - 1 S3 Bucket (Storage Service)
  - 1 Manager Instance (EC2) and as many workers as necessary (500 reviews/Worker)

   With this configuration, everything worked fine but very slowly (more than 10 minutes / input file).


2. Then, we decided to reduce the number of reviews per Worker to process (200 reviews/Worker). This helped to reduce the
   running time to a bit less than 7 minutes... __Could we do better?__


3. During a local run, we realized that it sometimes took quite a long time for the Manager to receive reports from the Workers.  
   The reason was that because all the Workers, and the Manager shared the same queue both for sending and receiving, some Workers
   could receive messages that wasn't addressed to them. Because of the `VisibilityTimeOut` and other random factors, both
   the Manager and the Workers could waste a lot of time waiting for messages.  
   In order to solve this problem, we though the tradeoff of adding two more queues (Local &lrarr; Manager &lrarr; Workers),
   was acceptable to reduce unnecessary waits.

Finally, we used the following configuration:

- [x] 4 SQS - Local &lrarr; Manager &lrarr; Workers,
- [x] 1 S3 Bucket (Storage Service),
- [x] 1 Manager Instance (EC2) and as many workers as necessary (200 reviews/Worker)

With this configuration, we reached simulation x5 faster than in the original try (A bit more than 2 minutes for some inputs).

## Scalability

### Jobs partition

The input files are build so that every line is a JSONObject that contains several reviews associated to a specific
product. From what we have noticed there are usually 10 reviews per JSONObject.  
However, if somehow one of the JSONObject contains 1000 reviews and another only 5, we don't want for a worker to
receive such an amount of jobs. For that reason, the manager unfold all the reviews in the input file and sends to the
workers several job requests with 10 reviews to process (except maybe for the last request that can contain less). That
way we know for sure every worker will have the same jobs to process.

### Workers

As we have seen [above](#jobs-partition), the jobs consist in a collection of 10 reviews. In order to maximize the task
completion, we decided to attribute to every worker 20 jobs (200 reviews/worker). That is, if a task contains 500
reviews to process, 3 workers will be created.

### Manager // TODO

### Simple Queue Service (SQS) // TODO

## Security

The security is an important part of our design. We don't want the public (potentially malicious) to have access to our
instances and enjoy free amazon services at our expense. In order to prevent these kinds of intrusions, we protected our
services with:

* IAM role: EC2-user (arn:aws:iam::752625253392:instance-profile/EC2-role) that grand our EC2 nodes full access to AWS
  services
  __without hardcoding credentials__!

* Storing the jar files on a random S3 (the first Local Application that starts the Manager upload the jar files
  __`compresed with a password`__, randomly defined during initialization and passed as user-data.

* We created a security-groups (sg-08106b9ce6c226627) that allows SSH connection only to only our IP addresses.
  Moreover, if somehow someone has access to one of our machines, the SSH key is password protected
  ([96 bits randomly generated](https://www.ssh.com/academy/iam/password/generator?)).

* We tried encrypting our data with a [Key Management Service of AWS](https://aws.amazon.com/kms/?nc1=h_ls) (KMS) but
  although this is maybe the best way to protect our data we didn't have the time for the research and implementation.
  Maybe for a future project...

* When an Application calls `terminate`, not only the Workers AND Manager instances are __terminated__ (not just shitted
  down)

* Overall, we could always add more security layers, but for now our account is limited to $50 and no bank accounts or
  credit cards are registered to out account.

#### For more information

[A Safer Way to Distribute AWS Credentials to EC2](https://aws.amazon.com/fr/blogs/security/a-safer-way-to-distribute-aws-credentials-to-ec2/)  
[10 security items to improve in your AWS account](https://aws.amazon.com/fr/blogs/security/top-10-security-items-to-improve-in-your-aws-account/#:~:text=MFA%20is%20the%20best%20way,you%20can%20enforce%20MFA%20there.)

## SQS Messages

### Local &rarr; Manager

* Attributes
  * `Name` : "New Task"
  * `Task` : unique `taskId` that will be used for the answer
  * `Type` : "Task"

* Body

```json
{
  "review-file-location": "location_of_the_file_with_the_reviews_in_s3(.txt)",
  "task-id": "unique task id used for the answer",
  "N": "How many workers are requiered to process the file (Integer)",
  "terminate": "true or false"
}
```

> If the terminate field is `true` the manager will terminate all the workers once the task is done.

### Manager &rarr; Local

#### Report ready
* Attributes
  * `Name` : "Task completed"
  * `Target` : unique `taskId` transmitted during task request (to target the correct sender)
  * `Type` : "Report"

* Body

```json
{
  "report-file-location": "location_of_the_file_with_the_report_in_s3(.txt)",
  "task-id": "unique task id used for the answer (must be equal to the one sent during request"
}
```

#### Terminate
* Attributes
  * `Name` : "Terminated"
  * `Terminate` : unique `taskId` transmitted during task request (to target the correct sender)
  * `Type` : "Terminate"

* Body: "Terminate"
> This message is only sent once when all the Workers instances have terminated and all the LocalApplications that sent a
> task request before the `terminate` have completed and received their reports

### Manager &rarr; Worker(s)

* Attributes
  * `Name` : "New Job"
  * `Sender` : unique `taskId` __created by the manager__ to keep track of the jobs senders
  * `Type` : "Job"

* Body

```json
{
  "id": "R14D3WP6J91DCU",
  "link": "https://www.amazon.com/gp/customer-reviews/R14D3WP6J91DCU/ref=cm_cr_arp_d_rvw_ttl?ie=UTF8&ASIN=0689835604",
  "title": "Five Stars",
  "text": "Super cute book. My son loves lifting the flaps.",
  "rating": 5,
  "author": "Nikki J",
  "date": "2017-05-01T21:00:00.000Z"
}
```
> The body is a single review in the JSON format to be processed by a worker

### Worker &rarr; Manager

* Attributes
  * `Name` : "Job completed:\t" + job_name + "\tFrom: " + sender
  * `Sender` : Same `Sender` attribute sent by the manager
  * `Type` : "Job Completed"

* Body

```json
{
  "link" : "https://www.amazon.com/gp/customer-reviews/R14D3WP6J91DCU/ref=cm_cr_arp_d_rvw_ttl?ie=UTF8&ASIN=0689835604",
  "rating" : 5,
  "sentiment" : 4,
  "entities" : "[Paris:LOCATION]"
}
```
> The body is a single review report in the JSON format to be added to the report of the `Sender`

## Issues encountered

* Due to our student account, we are limited to 19 instances (1 manager and 18 workers). To prevent our account to be
  blocked, we limited the amount of workers to 15. Hence, if somehow a task requires processing more than 3000 reviews,
  we won't be able to create more workers, and the amount of jobs per worker would grow (as well as the task's
  completion time).


* Let for a second suppose that the number of EC2 instances that we can crete isn't an issue (we can create as many
  workers as we want); in this case, if a lot of tasks (millions) are sent to the manager (by millions different local
  applications), the manager will be overwhelmed very fast as well as the sqs responsible to distribute job requests to
  the workers.

  * One of the solution would be to add more queues &rarr; more thread on the manager side (one for each queue) &rarr;
    concurrency issues ?

  * Maybe there is a need for more managers? &rarr; How to keep the scalability of the system ?

    * One option is to add a layer of Manager (in a tree manner) a randomly distribute the jobs to different side

    * The main Manager will then merge the reports of the two Managers subordinates and send the result to the
      corresponding local

    * That would mean 2<sup>layers</sup>-1 Managers' instances. Is that better ?


* Another issue we found in the assignment is the jobs' repartition:
  ```txt
  "If there are k active workers, and the new job requires m workers, then the manager should create m-k new workers, if possible"
  ```  
  The problem is that if millions of tasks only require 3 workers (<600 jobs per task), these 3 workers will have to
  deal with the millions of job alone. For this reason, we keep a count of the `pending job` and when a new task is
  received by the manager, it will take into account the number of pending jobs in order to create more workers if
  necessary.  
  For instance, there are currently `3 workers` running, `450 jobs pending` and `N = 200`. A new task arrives
  with `500 new jobs`. In total, there are now `950 jobs pending` for only 3 workers. Hence, `ceil(950/200) - 3 = _2_`
  more workers will be created.

## Todo list

- [x] Remove the files locally and on s3 once we are done with them
- [ ] ~Create 4 queues (one on each side, c.f [SQS Message](#sqs-messages))~
- [x] Run everything on AWS and wait for a result (try with a few files first!)
- [x] Don't forget to modify the jar files on s3
- [ ] ~Separate the manager private classes into different files~
- [x] Check why that many workers are created for a 50 lines input
- [x] Run two localApplications to see if the manager still handler it
- [x] Check for scalability 
