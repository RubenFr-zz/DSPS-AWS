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
	* `Report` : Location of the JSON report file
	* `TaskId` : unique `taskId` transmitted during task request

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
