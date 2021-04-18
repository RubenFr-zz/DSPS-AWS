# DSPS-AWS1
First assignement in the course DSPS 2021B - Introduction to AWS in Java

## SQS Messages

### Local -> Manager
* Attributes
> * `Name` : "New Task"
> * `Task` : unique `taskId` that will be used for the answer

* Body
```json
{
	"review-file-location" : "location_of_the_file_with_the_reviews_in_s3(.txt)",
	"task-id" : "unique task id used for the answer",
	"N" : "How many workers are requiered to process the file (Integer)"
}
```
