# Hotel Booking System

A Java 17 hotel-booking demo with an AWS serverless deployment:

- **Amazon S3** serves the static hotel website.
- **Amazon CloudFront** serves the site over HTTPS while keeping the S3 bucket private.
- **Amazon API Gateway HTTP API** exposes the booking API over HTTPS.
- **AWS Lambda** runs the Java API without an always-on server.
- **Amazon DynamoDB** stores bookings and per-room nightly reservations. Conditional transactions prevent two guests from reserving the same room-night, and strongly consistent guest lookups show new reservations immediately.
- **Amazon Cognito** verifies guest email addresses and protects booking lookup, creation, extension, and cancellation with JWT authorization.
- **AWS IAM and CloudWatch Logs** provide the Lambda's narrowly scoped access and short-retention runtime logs.

The website and API both use HTTPS. The S3 bucket is private and can only be read by its CloudFront distribution. No custom domain is required. Do not enter real payment details: payment and refunds remain simulations, and the decorative QR pattern is not scannable.

## Cost expectations

This design avoids always-on compute, NAT Gateways, load balancers, Fargate, EC2, and a custom domain. Lambda, API Gateway, DynamoDB on-demand, Cognito, S3, CloudFront, and CloudWatch have usage-based charges and may have free-tier allowances, but **AWS charges are not guaranteed to be zero**. Eligibility depends on your account, region, account age, and current AWS pricing; usage above allowances can incur charges. Check the AWS pricing pages for your account and region before deploying, and remove resources when finished.

No VPC is provisioned: API Gateway, Lambda, S3, Cognito, and DynamoDB communicate through AWS-managed service endpoints, so a VPC, NAT Gateway, or VPC endpoint is unnecessary for this application. Avoiding them keeps the idle cost lower. Fargate and EC2 are intentionally not used because they can incur charges while running even when there is no booking traffic.

## Deploy to AWS

Prerequisites: Java 17, Maven, Terraform 1.5+, and AWS CLI credentials with permission to manage the resources in `infrastructure/terraform`. Choose an AWS region supported by Lambda Java 17, API Gateway, Cognito, and DynamoDB.

1. Build the shaded Lambda deployment JAR:

   ```sh
   mvn clean verify
   ```

2. Review and apply the infrastructure:

   ```sh
   cd infrastructure/terraform
   terraform init
   terraform fmt -check
   terraform validate
   terraform plan
   terraform apply
   ```

3. Open the `website_url` output. Create a guest account, verify the email code Cognito sends, then sign in before creating or managing bookings. `api_url` is the HTTPS API endpoint.

Terraform creates a unique S3 bucket name and uploads the website and generated `config.js`. The booking API is public for room availability; booking endpoints require a valid Cognito JWT and a verified email. A guest can only access bookings associated with their verified account email.

The Jenkins pipeline runs `mvn clean verify`; it does not deploy infrastructure. Review and apply Terraform separately so AWS resource creation and its potential charges are explicit.

The DynamoDB transaction model supports stays up to 98 nights because room-night holds, the booking record, and its guest lookup record are committed together in one DynamoDB transaction. The local demo retains its 365-night validation.

Destroy the stack after use:

```sh
terraform destroy
```

Terraform state contains resource identifiers and should be kept private. Do not commit `terraform.tfstate`, `.terraform/`, or account-specific `terraform.tfvars` files. AWS credentials are read from the standard AWS credential chain; do not put access keys in source files or Terraform variables.

## Run locally

The standalone local server is retained for development and does not require AWS:

```sh
mvn clean package
java -jar target/hotel-booking-system-1.0.0.jar
```

Open [http://localhost:8080](http://localhost:8080). Local bookings are written to `data/bookings.tsv` by default. Set `PORT` or `BOOKINGS_FILE` to change the listener port or booking file location. The local server intentionally has no account authentication and must not be exposed to an untrusted network.

You can also run the local app in Docker:

```sh
docker build -t hotel-booking-system:local .
docker run --rm -p 8080:8080 -v hotel-booking-data:/data hotel-booking-system:local
```

## API

- `GET /api/rooms?checkIn=YYYY-MM-DD&checkOut=YYYY-MM-DD` — list rooms and availability; public in the AWS deployment.
- `POST /api/bookings` — create a booking using `name`, `email`, `roomId`, `guests`, `checkIn`, and `checkOut`; authenticated in AWS.
- `GET /api/bookings` — list bookings for the authenticated guest; the AWS API uses the verified Cognito email, not a caller-supplied identity.
- `POST /api/bookings/{id}/extend` — extend a stay using `checkOut`; authenticated in AWS.
- `POST /api/bookings/{id}/cancel` — cancel a booking; authenticated in AWS.
- `GET /healthz` — health check.

The demo cancellation policy applies a 50% fee/refund and returns a simulated transaction reference. No payment provider is integrated.
